package bank2sheets.sheets;

import bank2sheets.config.SheetsConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared fixtures for Google Sheets tests: a throwaway service account JSON
 * (test-only RSA key used only to exercise JWT signing against the fake token
 * endpoint) plus the config and token stub that go with it. Extracted so the
 * key lives in exactly one place, reused by the client tests and the Main sync
 * end-to-end tests.
 */
public final class SheetsTestSupport {

    /**
     * Throwaway RSA key (not a real credential) used only to exercise JWT signing
     * against the fake token endpoint.
     *
     * <p>ponytail: static fixture is deterministic and simple; if embedding key
     * material in tests ever becomes a concern, generate at runtime instead
     * (KeyPairGenerator, ~8 lines).
     */
    private static final String TEST_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCeqSl6z2T08EyT
            028+UNG3vdtQnSidq6cUz+xATEgyN4v41uWG0TYLNxsRRGetqFcT032/aj+OYOsb
            DQ6XsAL10mBfsps1mrpiqFOtQUPera1OhsW4QldGvo4plRRZoW0yhuhbXKv3SFMy
            Yr6mzjmM/1AJlYQDcCMXs3vH8bQr2mNKBNmFA7g3RLp9PVaV4RHVlZn/q2tqqMes
            cTGXOilvNHHx/VnkYEccYGBGby1Q2EcDm6TKF3TtPeprSH7EdaSjrGzk6LHMbEgR
            5gy/Qo87KAwOV5SRzBci4iS3CSEu8B4jmGDUsSPfUuVZL85l0x6Hgt/8HAwtcO7J
            rBY39B51AgMBAAECggEAAwjD0lAaY39093QZBzK50ARsJ/kuyFUlYjCCbs2xU8SG
            2MDDWEtdTTmbOulOp5414XCEpx8mYq1X0bHzkChoO2tFNzn4iJM88ptlfkVmluDw
            v4pe8vcnKXiGH3rKY4HCz4SPolpTWkH31VmDa+KQbIc1cKQmB4Ai1laIYiUtMj7N
            5n8oisZVcBXbIPTthvcluiKVJWcefMmIb7qk+ntPAUopAMv8TYlgKaXBdpnnCBSr
            A1IKyIeRKEPn5uzE4symsO5VnP/FdO2sFCCsp55YpG3nkcv1lU0VTax9A4xbKW7I
            A8w7eTgTqnhELpAT7MyUpbLX9KUnw3nT+SpeLuImwQKBgQDEDGkaNPPQhpciUMds
            4HLH/Wp5BZf/95/vaTBRSfR/ZS+0//gIG1DE8dfGFwmP5mdkAxuU+pEAM/xbVpE5
            DHrQIWoadqjb8mLyP446TY0/tm+MU2eMMOU1dBRnEW1efrZ3tphZhBDtibkDqyRr
            5Bm+NtYac6gEq5WPjbanPAl3RwKBgQDPLd5lC6l2LLy70ggycgZio0VrmeKxdcD4
            JB872b28KeqrdNCIfLwY+E2voIw54Phszv25xT2rOdsf7rk3pSWOMbWMB9bBSIMj
            V+iMHtk0zoynAh1eLj8h78ylrGjS745TG4UUXPkWOQTqFtetOKI0Rm4RWRyGSflx
            JFTivtgSYwKBgFtLiDo7BHtL4d+KDIRoNStqmIWJFFKPxvvfWS/Rp7kXrtK0DJYB
            2mgMUzYdDkfjvKv3QoXq9VFJ5C/IrVIKvLplhRqTvWjHhEpZTGtcJ/XOiD2prWO8
            dVCiIMoqdGr3yRMQhfvRo2xqoSzTKt/PVimdMbEZwNmD6d7y2GcibyoLAoGBAMqP
            gj0xJcKRAwZv1+vaaDvQfnMxxd7TzpPOXWta/JMYOITidXC84/tV6j6orfF47+sS
            hig3nI/oxUF3Wkf5g7p/B42drG7VwsGvlYJKNvvWZtZyztviQ3+ewWvKIcL6zSCc
            a4T5UeBUZ2WoZ77I/G433i2//o+di/D/IK+BACkZAoGBAJhgyo5KLKELaUHAAEQs
            T3X2vRM3oauVVT+Poxjt8oLKf0Rq0MNwAJujmmcUmYOEhQa6bt3jheImjpgFqST7
            pGD4AE4hHQeRHA27qdHw6whG4aaaEg2E/O3NSrAutL6CO8rCCzd9sY7dQUpuIVMU
            yVgkJC4zzj4Ku6B0EmmmDrdi
            -----END PRIVATE KEY-----
            """;

    private SheetsTestSupport() {
    }

    /** SheetsConfig backed by the throwaway service account and spreadsheet id "sheet-1". */
    public static SheetsConfig config() {
        return new SheetsConfig(serviceAccountB64(), "sheet-1");
    }

    /** Base64 of a throwaway service account JSON used only to exercise JWT signing. */
    public static String serviceAccountB64() {
        String json = """
                {"type":"service_account",
                 "client_email":"bank2sheets@test.iam.gserviceaccount.com",
                 "private_key":"%s",
                 "token_uri":"https://oauth2.googleapis.com/token"}
                """.formatted(TEST_PRIVATE_KEY.strip().replace("\n", "\\n"));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Stubs the OAuth token endpoint that {@link GoogleSheetsClient#accessToken()} calls. */
    public static void stubToken(FakeSheetsHttp http) {
        http.stub("https://oauth2.googleapis.com/token",
                "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":3599}");
    }
}
