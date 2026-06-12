package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Account Service REST endpoints.
 *
 * Each test uses unique accountIds and transactionIds to avoid conflicts when
 * tests share the same H2 database within the Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AccountApiIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void applyCredit_returns201WithCorrectNewBalance() throws Exception {
        mockMvc.perform(post("/accounts/acc-unit-cr/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-cr-1", "CREDIT", 100.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("txn-unit-cr-1"))
                .andExpect(jsonPath("$.accountId").value("acc-unit-cr"))
                .andExpect(jsonPath("$.newBalance").value(100.0))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void applyDebit_afterCredit_returns201WithReducedBalance() throws Exception {
        mockMvc.perform(post("/accounts/acc-unit-db/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-db-cr", "CREDIT", 200.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/acc-unit-db/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-db-db", "DEBIT", 75.0, "2026-06-01T11:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newBalance").value(125.0));
    }

    @Test
    void applyTransaction_duplicate_returns201WithUnchangedBalance() throws Exception {
        mockMvc.perform(post("/accounts/acc-unit-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-idem-1", "CREDIT", 50.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newBalance").value(50.0));

        // Same transactionId — idempotency check prevents double-apply
        mockMvc.perform(post("/accounts/acc-unit-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-idem-1", "CREDIT", 50.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newBalance").value(50.0)); // balance unchanged
    }

    @Test
    void getBalance_existingAccount_returns200WithBalance() throws Exception {
        mockMvc.perform(post("/accounts/acc-unit-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-bal-1", "CREDIT", 250.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acc-unit-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-unit-bal"))
                .andExpect(jsonPath("$.balance").value(250.0))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void getBalance_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/accounts/acc-does-not-exist-xyz/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getAccount_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/accounts/acc-also-not-there-xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAccount_existingAccount_returnsTransactionHistory() throws Exception {
        mockMvc.perform(post("/accounts/acc-unit-hist/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-hist-1", "CREDIT", 100.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acc-unit-hist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-unit-hist"))
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].transactionId").value("txn-unit-hist-1"));
    }

    @Test
    void getAccount_outOfOrderTransactions_historyIsSortedByEventTimestamp() throws Exception {
        // Submit latest timestamp first
        mockMvc.perform(post("/accounts/acc-unit-ooo/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-ooo-c", "CREDIT", 30.0, "2026-06-03T10:00:00Z")))
                .andExpect(status().isCreated());

        // Submit earliest timestamp second
        mockMvc.perform(post("/accounts/acc-unit-ooo/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-ooo-a", "CREDIT", 10.0, "2026-06-01T10:00:00Z")))
                .andExpect(status().isCreated());

        // Submit middle timestamp last
        mockMvc.perform(post("/accounts/acc-unit-ooo/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txJson("txn-unit-ooo-b", "CREDIT", 20.0, "2026-06-02T10:00:00Z")))
                .andExpect(status().isCreated());

        // Final balance is 60 regardless of submission order (commutative)
        // Transaction history is sorted by eventTimestamp, not insertion order
        mockMvc.perform(get("/accounts/acc-unit-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(60.0))
                .andExpect(jsonPath("$.transactions[0].transactionId").value("txn-unit-ooo-a"))
                .andExpect(jsonPath("$.transactions[1].transactionId").value("txn-unit-ooo-b"))
                .andExpect(jsonPath("$.transactions[2].transactionId").value("txn-unit-ooo-c"));
    }

    // --- helpers ---

    private String txJson(String transactionId, String type, double amount, String timestamp) {
        return """
                {
                    "transactionId": "%s",
                    "type": "%s",
                    "amount": %.2f,
                    "currency": "USD",
                    "eventTimestamp": "%s"
                }
                """.formatted(transactionId, type, amount, timestamp);
    }
}
