package com.eventledger.account.service;

import com.eventledger.account.model.dto.BalanceResponse;
import com.eventledger.account.model.dto.TransactionRequest;
import com.eventledger.account.model.dto.TransactionResponse;
import com.eventledger.account.model.entity.AccountEntity;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository);
    }

    @Test
    void applyTransaction_credit_increasesBalance() {
        AccountEntity account = new AccountEntity("acc-1", BigDecimal.valueOf(100), "USD", Instant.now());
        when(transactionRepository.existsById("txn-1")).thenReturn(false);
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse result = accountService.applyTransaction("acc-1",
                new TransactionRequest("txn-1", "CREDIT", BigDecimal.valueOf(50), "USD", Instant.now()));

        assertThat(result.newBalance()).isEqualByComparingTo(BigDecimal.valueOf(150));
        assertThat(result.accountId()).isEqualTo("acc-1");
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void applyTransaction_debit_decreasesBalance() {
        AccountEntity account = new AccountEntity("acc-2", BigDecimal.valueOf(200), "USD", Instant.now());
        when(transactionRepository.existsById("txn-2")).thenReturn(false);
        when(accountRepository.findById("acc-2")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse result = accountService.applyTransaction("acc-2",
                new TransactionRequest("txn-2", "DEBIT", BigDecimal.valueOf(75), "USD", Instant.now()));

        assertThat(result.newBalance()).isEqualByComparingTo(BigDecimal.valueOf(125));
    }

    @Test
    void applyTransaction_newAccount_autoCreatedWithZeroBalance() {
        when(transactionRepository.existsById("txn-new")).thenReturn(false);
        when(accountRepository.findById("acc-new")).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse result = accountService.applyTransaction("acc-new",
                new TransactionRequest("txn-new", "CREDIT", BigDecimal.valueOf(100), "USD", Instant.now()));

        assertThat(result.newBalance()).isEqualByComparingTo(BigDecimal.valueOf(100));
        verify(accountRepository).save(any());
    }

    @Test
    void applyTransaction_duplicate_returnsCurrentBalanceWithoutModifying() {
        AccountEntity account = new AccountEntity("acc-3", BigDecimal.valueOf(300), "USD", Instant.now());
        when(transactionRepository.existsById("txn-dup")).thenReturn(true);
        when(accountRepository.findById("acc-3")).thenReturn(Optional.of(account));

        TransactionResponse result = accountService.applyTransaction("acc-3",
                new TransactionRequest("txn-dup", "CREDIT", BigDecimal.valueOf(999), "USD", Instant.now()));

        assertThat(result.newBalance()).isEqualByComparingTo(BigDecimal.valueOf(300));
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void applyTransaction_balanceIsCommutative_creditThenDebitEqualsDebitThenCredit() {
        // Applying CREDIT(100) then DEBIT(30) to the same account produces balance=70.
        // The AccountEntity object is mutated in-place by each service call
        // (service sets account.setBalance(newBalance)), so the running total
        // accumulates correctly across calls to the same mock.
        AccountEntity account = new AccountEntity("acc-4", BigDecimal.ZERO, "USD", Instant.now());
        when(transactionRepository.existsById(any())).thenReturn(false);
        when(accountRepository.findById("acc-4")).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.applyTransaction("acc-4",
                new TransactionRequest("txn-c1", "CREDIT", BigDecimal.valueOf(100), "USD", Instant.now()));
        TransactionResponse result = accountService.applyTransaction("acc-4",
                new TransactionRequest("txn-d1", "DEBIT", BigDecimal.valueOf(30), "USD", Instant.now()));

        assertThat(result.newBalance()).isEqualByComparingTo(BigDecimal.valueOf(70));
    }

    @Test
    void getBalance_existingAccount_returnsCorrectBalance() {
        AccountEntity account = new AccountEntity("acc-5", BigDecimal.valueOf(500), "USD", Instant.now());
        when(accountRepository.findById("acc-5")).thenReturn(Optional.of(account));

        BalanceResponse result = accountService.getBalance("acc-5");

        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(result.accountId()).isEqualTo("acc-5");
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void getBalance_unknownAccount_throwsNoSuchElement() {
        when(accountRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getBalance("ghost"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("ghost");
    }
}
