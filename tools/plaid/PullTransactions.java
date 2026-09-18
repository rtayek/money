package com.plaid.quickstart;

import com.plaid.client.ApiClient;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone pull of every transaction on one linked account, via Plaid's
 * /transactions/sync, with the raw bank-statement descriptor included
 * (the "GOOGLE*MERGE DRAGONS..." style text that Simplifi's own CSV export
 * strips out). Writes plaid-transactions.csv in the current directory.
 *
 * Reads PLAID_CLIENT_ID, PLAID_SECRET, PLAID_ENV, and PLAID_ACCESS_TOKEN
 * from environment variables -- the same ones already in the Quickstart's
 * .env file, plus PLAID_ACCESS_TOKEN, which you add yourself after linking
 * an account through the Quickstart's Link flow.
 *
 * One access token covers one login (one Item). A login can carry several
 * cards, so each row is tagged with its AccountId and a readable Account
 * name+mask, letting one pull separate multiple cards and letting several
 * pulls (a card under a different login, e.g. a spouse's) be combined.
 *
 * Run (from quickstart/java, after `mvn clean package`), optional output name:
 *   env $(cat ../.env | grep -v "#" | xargs) \
 *     java -cp target/quickstart-1.0-SNAPSHOT.jar com.plaid.quickstart.PullTransactions [output.csv]
 */
public final class PullTransactions {

    public static void main(String[] args) throws IOException {
        String clientId = require("PLAID_CLIENT_ID");
        String secret = require("PLAID_SECRET");
        String accessToken = require("PLAID_ACCESS_TOKEN");
        String env = System.getenv().getOrDefault("PLAID_ENV", "sandbox");
        Path out = Path.of(args.length > 0 ? args[0] : "plaid-transactions.csv");

        HashMap<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);
        apiKeys.put("plaidVersion", "2020-09-14");
        ApiClient apiClient = new ApiClient(apiKeys);
        apiClient.setPlaidAdapter("production".equals(env) ? ApiClient.Production : ApiClient.Sandbox);
        PlaidApi plaidClient = apiClient.createService(PlaidApi.class);

        List<Transaction> all = new ArrayList<>();
        Map<String, String> accountLabels = new LinkedHashMap<>();
        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            TransactionsSyncRequestOptions options = new TransactionsSyncRequestOptions()
                    .includeOriginalDescription(true);
            TransactionsSyncRequest request = new TransactionsSyncRequest()
                    .accessToken(accessToken)
                    .cursor(cursor)
                    .options(options);

            TransactionsSyncResponse response;
            try {
                response = plaidClient.transactionsSync(request).execute().body();
            } catch (IOException e) {
                throw new IOException("Plaid request failed: " + e.getMessage(), e);
            }
            if (response == null) {
                throw new IOException("Plaid returned an empty response -- check your access token and secret.");
            }

            if (response.getAccounts() != null) {
                for (AccountBase a : response.getAccounts()) {
                    accountLabels.put(a.getAccountId(), accountLabel(a));
                }
            }
            all.addAll(response.getAdded());
            hasMore = response.getHasMore();
            cursor = response.getNextCursor();
        }

        System.out.printf(Locale.US, "Pulled %d transactions across %d account(s).%n",
                all.size(), accountLabels.size());

        List<String> lines = new ArrayList<>();
        lines.add("Date,AccountId,Account,Name,MerchantName,OriginalDescription,Category,Amount");
        for (Transaction t : all) {
            String category = (t.getCategory() == null || t.getCategory().isEmpty())
                    ? "" : String.join(" > ", t.getCategory());
            lines.add(String.join(",",
                    csv(String.valueOf(t.getDate())),
                    csv(t.getAccountId()),
                    csv(accountLabels.getOrDefault(t.getAccountId(), "")),
                    csv(t.getName()),
                    csv(t.getMerchantName()),
                    csv(t.getOriginalDescription()),
                    csv(category),
                    csv(String.valueOf(t.getAmount()))));
        }

        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    /** Readable account label such as "Delta SkyMiles Gold (1234)". */
    private static String accountLabel(AccountBase a) {
        String name = a.getName() != null ? a.getName() : a.getOfficialName();
        if (name == null) name = "";
        return a.getMask() != null ? name + " (" + a.getMask() + ")" : name;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " (in .env or as an environment variable) before running this.");
        }
        return value;
    }

    /** Minimal CSV field escaping; treats null as an empty field. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}