package bank2sheets.openbanking.saltedge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.config.AccountConfig;
import bank2sheets.config.AppConfig;
import bank2sheets.model.Account;
import bank2sheets.model.Transaction;
import bank2sheets.http.HttpExchange.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SaltEdgeClient tests against an in-memory fake HTTP backend; no real
 * credentials or network calls are involved.
 */
class SaltEdgeClientTest {

    @Test
    void connectSendsAisOnlyConsentAndReturnsUrl() {
        FakeHttp http = new FakeHttp();
        http.stub("/connect_sessions/create",
                """
                {"data": {"connect_url": "https://www.saltedge.com/connect?token=t1",
                          "expires_at": "2026-08-05T00:00:00Z"}}
                """);
        SaltEdgeClient client = new SaltEdgeClient(config(), http);

        String url = client.connect();

        assertEquals("https://www.saltedge.com/connect?token=t1", url);
        assertEquals(1, http.requests.size());
        FakeHttp.Request request = http.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals(FakeHttp.BASE + "/connect_sessions/create", request.url());
        assertEquals("app-1", request.headers().get("App-id"));
        assertEquals("secret-1", request.headers().get("Secret"));
        JsonObject data = JsonParser.parseString(request.body()).getAsJsonObject().getAsJsonObject("data");
        assertEquals("cust-1", data.get("customer_id").getAsString());
        List<String> scopes = new ArrayList<>();
        for (JsonElement scope : data.getAsJsonObject("consent").getAsJsonArray("scopes")) {
            scopes.add(scope.getAsString());
        }
        assertEquals(List.of("account_details", "transactions_details"), scopes);
        assertEquals("http://localhost:5173/bank2sheets/connected",
                data.getAsJsonObject("attempt").get("return_to").getAsString());
    }

    @Test
    void connectFailsFastWithoutCustomerId() {
        AppConfig noCustomer = new AppConfig("app-1", "secret-1", "", "conn-1", List.of());
        SaltEdgeClient client = new SaltEdgeClient(noCustomer, new FakeHttp());
        assertThrows(SaltEdgeException.class, client::connect);
    }

    @Test
    void findConnectionIdReturnsNewestConnection() {
        FakeHttp http = new FakeHttp();
        http.stub("/connections?customer_id=cust-1",
                """
                {"data": [
                  {"id": "conn-old", "status": "active", "created_at": "2026-01-01T10:00:00Z"},
                  {"id": "conn-new", "status": "active", "created_at": "2026-08-04T10:00:00Z"}
                ]}
                """);
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        assertEquals("conn-new", client.findConnectionId());
    }

    @Test
    void findConnectionIdFailsWithoutConnections() {
        FakeHttp http = new FakeHttp();
        http.stub("/connections?customer_id=cust-1", "{\"data\": []}");
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        assertThrows(SaltEdgeException.class, client::findConnectionId);
    }

    @Test
    void isActiveReflectsConnectionStatus() {
        FakeHttp http = new FakeHttp();
        http.stub("/connections/conn-1", "{\"data\": {\"id\": \"conn-1\", \"status\": \"active\"}}");
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        assertTrue(client.isActive("conn-1"));
    }

    @Test
    void isActiveFalseWhilePending() {
        FakeHttp http = new FakeHttp();
        http.stub("/connections/conn-1", "{\"data\": {\"id\": \"conn-1\", \"status\": \"pending\"}}");
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        assertFalse(client.isActive("conn-1"));
    }

    @Test
    void listAccountsMapsIdAndName() {
        FakeHttp http = new FakeHttp();
        http.stub("/accounts?connection_id=conn-1",
                """
                {"data": [
                  {"id": "acct-1", "name": "Cuenta BBVA", "nature": "checking"},
                  {"id": "acct-2", "nature": "credit_card"}
                ]}
                """);
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        assertEquals(
                List.of(new Account("acct-1", "Cuenta BBVA"), new Account("acct-2", "")),
                client.listAccounts());
    }

    @Test
    void fetchTransactionsPaginatesAndNormalizesPayload() {
        FakeHttp http = new FakeHttp();
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1",
                """
                {"meta": {"next_id": "p2"}, "data": [
                  {"id": "t1", "description": "Nomina", "amount": 1200.50, "currency_code": "EUR",
                   "made_on": "2026-08-01T10:30:00Z"},
                  {"id": "t2", "description": "Cafe", "amount": "-3.20", "currency_code": "EUR",
                   "made_on": "2026-08-02"}
                ]}
                """);
        http.stub("/transactions?connection_id=conn-1&account_id=acct-1&next_id=p2",
                """
                {"data": [
                  {"id": "t3", "description": "Alquiler", "amount": 800, "currency_code": "EUR",
                   "made_on": "2026-08-03T09:00:00+02:00"}
                ]}
                """);
        SaltEdgeClient client = new SaltEdgeClient(config(), http);

        List<Transaction> transactions = client.fetchTransactions("acct-1");

        assertEquals(3, transactions.size());
        assertEquals(new Transaction("t1", "Nomina", new BigDecimal("1200.50"), "EUR", LocalDate.of(2026, 8, 1)),
                transactions.get(0));
        assertEquals(new Transaction("t2", "Cafe", new BigDecimal("-3.20"), "EUR", LocalDate.of(2026, 8, 2)),
                transactions.get(1));
        assertEquals(new Transaction("t3", "Alquiler", new BigDecimal("800"), "EUR", LocalDate.of(2026, 8, 3)),
                transactions.get(2));
        assertEquals(2, http.requests.size());
        assertTrue(http.requests.get(1).url().endsWith("&next_id=p2"));
    }

    @Test
    void nonSuccessStatusThrows() {
        FakeHttp http = new FakeHttp();
        http.responses.put("/connections/conn-1",
                new Response(401, "{\"error\": {\"class\": \"ApiKeyNotFound\", \"message\": \"bad keys\"}}"));
        SaltEdgeClient client = new SaltEdgeClient(config(), http);
        SaltEdgeException error = assertThrows(SaltEdgeException.class, () -> client.isActive("conn-1"));
        assertTrue(error.getMessage().contains("401"));
    }

    private static AppConfig config() {
        return new AppConfig("app-1", "secret-1", "cust-1", "conn-1",
                List.of(new AccountConfig("saltedge", "acct-1")));
    }
}
