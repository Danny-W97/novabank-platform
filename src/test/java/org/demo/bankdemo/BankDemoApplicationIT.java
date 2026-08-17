package org.demo.bankdemo;

import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.card.CardTransactionRepository;
import org.demo.bankdemo.card.DemoCardRepository;
import org.demo.bankdemo.auth.LoginAttemptService;
import org.demo.bankdemo.audit.OperationAuditRepository;
import org.demo.bankdemo.audit.LoginHistoryRepository;
import org.demo.bankdemo.admin.AdminStatusChangeRepository;
import org.demo.bankdemo.auth.EmailVerificationTokenRepository;
import org.demo.bankdemo.auth.PasswordResetTokenRepository;
import org.demo.bankdemo.notification.NotificationRepository;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationRepository;
import org.demo.bankdemo.profile.TotpRecoveryCodeRepository;
import org.demo.bankdemo.currency.CurrencyBalanceRepository;
import org.demo.bankdemo.currency.ExchangeRateRepository;
import org.demo.bankdemo.currency.ExchangeTransactionRepository;
import org.demo.bankdemo.recipient.SavedRecipientRepository;
import org.demo.bankdemo.transfer.ScheduledTransferRepository;
import org.demo.bankdemo.dashboard.DashboardTransactionRepository;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
class BankDemoApplicationIT {

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    AccountRepository accountRepository;

    @MockitoBean
    TransactionRepository transactionRepository;

    @MockitoBean
    DashboardTransactionRepository dashboardTransactionRepository;

    @MockitoBean
    LoginAttemptService loginAttemptService;
    @MockitoBean
    OperationAuditRepository operationAuditRepository;

    @MockitoBean
    NotificationRepository notificationRepository;

    @MockitoBean
    SavedRecipientRepository savedRecipientRepository;

    @MockitoBean
    PaymentAuthorizationRepository paymentAuthorizationRepository;

    @MockitoBean
    TotpRecoveryCodeRepository totpRecoveryCodeRepository;

    @MockitoBean
    CurrencyBalanceRepository currencyBalanceRepository;

    @MockitoBean
    ExchangeRateRepository exchangeRateRepository;

    @MockitoBean
    ExchangeTransactionRepository exchangeTransactionRepository;

    @MockitoBean
    DemoCardRepository demoCardRepository;

    @MockitoBean
    CardTransactionRepository cardTransactionRepository;

    @MockitoBean
    AdminStatusChangeRepository adminStatusChangeRepository;

    @MockitoBean
    LoginHistoryRepository loginHistoryRepository;

    @MockitoBean
    EmailVerificationTokenRepository emailVerificationTokenRepository;

    @MockitoBean
    PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    ScheduledTransferRepository scheduledTransferRepository;

    @Test
    void contextLoads() {
    }
}
