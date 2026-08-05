package bank2sheets.sheets;

import bank2sheets.http.HttpExchange;
import bank2sheets.http.HttpExchange.Response;
import bank2sheets.openbanking.saltedge.FakeHttp;
import java.util.List;
import java.util.Map;

/**
 * Sheets-flavored view of the shared {@link FakeHttp}: request recording and
 * stubbing stay in one place (no duplicated stub logic), while this thin view
 * exposes them as the {@link HttpExchange} seam that {@link GoogleSheetsClient}
 * consumes, with a Google Sheets base URL. Both sides use the shared
 * {@code bank2sheets.http.HttpExchange.Response} type, so delegation is direct
 * (no type conversion). Stub keys for the Sheets API are relative to
 * {@code /v4/spreadsheets}; the OAuth token endpoint is keyed by its full URL
 * because it lives on a different host.
 */
public final class FakeSheetsHttp implements HttpExchange {

    private static final String GOOGLE_BASE = "https://sheets.googleapis.com/v4/spreadsheets";

    private final FakeHttp delegate = new FakeHttp(GOOGLE_BASE);

    public void stub(String pathAndQuery, String body) {
        delegate.stub(pathAndQuery, body);
    }

    public void stub(String pathAndQuery, int status, String body) {
        delegate.stub(pathAndQuery, status, body);
    }

    public List<FakeHttp.Request> requests() {
        return delegate.requests;
    }

    @Override
    public Response exchange(String method, String url, Map<String, String> headers, String body) {
        return delegate.exchange(method, url, headers, body);
    }
}
