package org.demo.bankdemo.admin;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class AdminService {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH).withZone(WARSAW);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OperationAuditService auditService;
    private final UserSessionService userSessionService;
    private final AdminStatusChangeRepository statusChangeRepository;

    @Autowired
    public AdminService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            OperationAuditService auditService,
            UserSessionService userSessionService,
            AdminStatusChangeRepository statusChangeRepository
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.userSessionService = userSessionService;
        this.statusChangeRepository = statusChangeRepository;
    }

    public AdminService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            OperationAuditService auditService,
            UserSessionService userSessionService
    ) {
        this(userRepository, accountRepository, transactionRepository, auditService, userSessionService, null);
    }

    @Transactional(readOnly = true)
    public AdminDashboard dashboard() {
        return dashboard(null);
    }

    @Transactional(readOnly = true)
    public AdminDashboard dashboard(String searchQuery) {
        var query = normalizeSearch(searchQuery);
        var accounts = query == null
                ? accountRepository.findAllWithOwners()
                : accountRepository.searchAllWithOwners(query);
        var statusChanges = statusChangeRepository == null
                ? List.<AdminStatusChangeView>of()
                : statusChangeRepository.findAllByOrderByCreatedAtDesc(
                        org.springframework.data.domain.PageRequest.of(0, 100)
                ).stream().map(this::toStatusChangeView).toList();
        return new AdminDashboard(
                userRepository.count(),
                accountRepository.count(),
                transactionRepository.count(),
                auditService.recentFailures(),
                accounts.stream().map(this::toManagedAccount).toList(),
                statusChanges,
                auditService.recentAdminActions()
        );
    }

    public void blockAccount(Long accountId, String adminEmail) {
        blockAccount(accountId, adminEmail, null);
    }

    @Transactional
    public void blockAccount(Long accountId, String adminEmail, String ipAddress) {
        Account account = null;
        try {
            account = accountFor(accountId);
            ensureNotSelf(account.getOwner().getEmail(), adminEmail);
            var previousAccountStatus = account.getStatus();
            var previousUserStatus = account.getOwner().getStatus();
            account.block();
            account.getOwner().block();
            userSessionService.invalidateAllSessions(account.getOwner().getEmail());
            recordStatusChange(account, previousAccountStatus, previousUserStatus, adminEmail,
                    "Blokada rachunku przez administratora", ipAddress);
            recordAdminSuccess(adminEmail, "ADMIN_ACCOUNT_BLOCK", account, ipAddress,
                    "Zablokowano rachunek i konto uzytkownika.");
        } catch (RuntimeException exception) {
            recordAdminFailure(adminEmail, "ADMIN_ACCOUNT_BLOCK", account, ipAddress, exception);
            throw exception;
        }
    }

    public void activateAccount(Long accountId, String adminEmail) {
        activateAccount(accountId, adminEmail, null);
    }

    @Transactional
    public void activateAccount(Long accountId, String adminEmail, String ipAddress) {
        Account account = null;
        try {
            account = accountFor(accountId);
            ensureNotSelf(account.getOwner().getEmail(), adminEmail);
            if (account.getStatus() == AccountStatus.CLOSED) {
                throw new IllegalArgumentException("Zamknietego rachunku nie mozna aktywowac.");
            }
            var previousAccountStatus = account.getStatus();
            var previousUserStatus = account.getOwner().getStatus();
            account.activate();
            account.getOwner().activate();
            recordStatusChange(account, previousAccountStatus, previousUserStatus, adminEmail,
                    "Aktywacja rachunku przez administratora", ipAddress);
            recordAdminSuccess(adminEmail, "ADMIN_ACCOUNT_ACTIVATE", account, ipAddress,
                    "Aktywowano rachunek i konto uzytkownika.");
        } catch (RuntimeException exception) {
            recordAdminFailure(adminEmail, "ADMIN_ACCOUNT_ACTIVATE", account, ipAddress, exception);
            throw exception;
        }
    }

    public void closeAccount(Long accountId, String adminEmail) {
        closeAccount(accountId, adminEmail, null);
    }

    @Transactional
    public void closeAccount(Long accountId, String adminEmail, String ipAddress) {
        Account account = null;
        try {
            account = accountFor(accountId);
            ensureNotSelf(account.getOwner().getEmail(), adminEmail);
            var previousAccountStatus = account.getStatus();
            var previousUserStatus = account.getOwner().getStatus();
            account.close();
            account.getOwner().block();
            userSessionService.invalidateAllSessions(account.getOwner().getEmail());
            recordStatusChange(account, previousAccountStatus, previousUserStatus, adminEmail,
                    "Zamkniecie rachunku przez administratora", ipAddress);
            recordAdminSuccess(adminEmail, "ADMIN_ACCOUNT_CLOSE", account, ipAddress,
                    "Zamknieto rachunek bez usuwania danych.");
        } catch (RuntimeException exception) {
            recordAdminFailure(adminEmail, "ADMIN_ACCOUNT_CLOSE", account, ipAddress, exception);
            throw exception;
        }
    }

    public int logoutSessions(Long accountId, String adminEmail) {
        return logoutSessions(accountId, adminEmail, null);
    }

    @Transactional
    public int logoutSessions(Long accountId, String adminEmail, String ipAddress) {
        Account account = null;
        try {
            account = accountFor(accountId);
            ensureNotSelf(account.getOwner().getEmail(), adminEmail);
            var removed = userSessionService.invalidateAllSessions(account.getOwner().getEmail());
            recordAdminSuccess(adminEmail, "ADMIN_LOGOUT_SESSIONS", account, ipAddress,
                    "Uniewazniono sesje uzytkownika: " + removed + ".");
            return removed;
        } catch (RuntimeException exception) {
            recordAdminFailure(adminEmail, "ADMIN_LOGOUT_SESSIONS", account, ipAddress, exception);
            throw exception;
        }
    }

    private Account accountFor(Long accountId) {
        return accountRepository.findByIdWithOwner(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono rachunku."));
    }

    private void recordStatusChange(
            Account account,
            AccountStatus previousAccountStatus,
            org.demo.bankdemo.user.UserStatus previousUserStatus,
            String adminEmail,
            String reason,
            String ipAddress
    ) {
        if (statusChangeRepository != null) {
            statusChangeRepository.save(new AdminStatusChange(
                    account.getId(),
                    account.getAccountNumber(),
                    account.getOwner().getEmail(),
                    previousAccountStatus == null ? null : previousAccountStatus.name(),
                    account.getStatus().name(),
                    previousUserStatus == null ? null : previousUserStatus.name(),
                    account.getOwner().getStatus().name(),
                    normalizeEmail(adminEmail),
                    reason,
                    ipAddress
            ));
        }
    }

    private void recordAdminSuccess(String adminEmail, String operation, Account account, String ipAddress, String details) {
        auditService.recordAdminAction(
                adminEmail,
                operation,
                account == null ? null : account.getOwner().getEmail(),
                account == null ? null : account.getId(),
                details,
                true,
                ipAddress
        );
    }

    private void recordAdminFailure(String adminEmail, String operation, Account account, String ipAddress, RuntimeException exception) {
        auditService.recordAdminAction(
                adminEmail,
                operation,
                account == null ? null : account.getOwner().getEmail(),
                account == null ? null : account.getId(),
                exception.getMessage(),
                false,
                ipAddress
        );
    }

    private static void ensureNotSelf(String ownerEmail, String adminEmail) {
        if (ownerEmail.equalsIgnoreCase(adminEmail)) {
            throw new IllegalArgumentException("Nie można zmienić statusu własnego konta administratora.");
        }
    }

    private ManagedAccount toManagedAccount(Account account) {
        var owner = account.getOwner();
        var status = account.getStatus();
        return new ManagedAccount(
                account.getId(), owner.getFullName(), owner.getEmail(),
                formatAccountNumber(account.getAccountNumber()), formatMoney(account.getBalance()),
                accountStatusLabel(status), userStatusLabel(owner.getStatus().name()),
                status == AccountStatus.ACTIVE, status == AccountStatus.BLOCKED,
                formatInstant(account.getCreatedAt()), formatInstant(owner.getLastLoginAt()),
                userSessionService.activeSessionCount(owner.getEmail())
        );
    }

    private AdminStatusChangeView toStatusChangeView(AdminStatusChange change) {
        return new AdminStatusChangeView(
                change.getId(), formatInstant(change.getCreatedAt()), change.getChangedBy(),
                change.getUserEmail(), formatAccountNumber(change.getAccountNumber()),
                change.getPreviousAccountStatus(), change.getNewAccountStatus(),
                change.getPreviousUserStatus(), change.getNewUserStatus(),
                change.getReason(), change.getIpAddress()
        );
    }

    private static String normalizeSearch(String search) {
        if (search == null || search.isBlank()) return null;
        return search.strip().substring(0, Math.min(100, search.strip().length()));
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String accountStatusLabel(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> "Aktywne";
            case BLOCKED -> "Zablokowane";
            case CLOSED -> "Zamkniete";
        };
    }

    private static String userStatusLabel(String status) {
        return "ACTIVE".equals(status) ? "Aktywny" : "Zablokowany";
    }

    private static String formatAccountNumber(String accountNumber) {
        var formatted = new StringBuilder(accountNumber.substring(0, 2));
        for (var index = 2; index < accountNumber.length(); index += 4) {
            formatted.append(' ').append(accountNumber, index, Math.min(index + 4, accountNumber.length()));
        }
        return formatted.toString();
    }

    private static String formatMoney(BigDecimal amount) {
        var symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator('\u00A0');
        return new DecimalFormat("#,##0.00", symbols).format(amount) + " PLN";
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "Brak danych" : DATE_TIME.format(instant);
    }

    public record AdminDashboard(
            long users,
            long accounts,
            long transactions,
            List<OperationAuditService.AuditView> audits,
            List<ManagedAccount> managedAccounts,
            List<AdminStatusChangeView> statusChanges,
            List<OperationAuditService.AdminAuditView> adminAudits
    ) {
        public AdminDashboard(long users, long accounts, long transactions, List<OperationAuditService.AuditView> audits) {
            this(users, accounts, transactions, audits, List.of(), List.of(), List.of());
        }

        public AdminDashboard(long users, long accounts, long transactions, List<OperationAuditService.AuditView> audits, List<ManagedAccount> managedAccounts) {
            this(users, accounts, transactions, audits, managedAccounts, List.of(), List.of());
        }
    }

    public record ManagedAccount(
            Long id, String fullName, String email, String accountNumber, String balance,
            String accountStatus, String userStatus, boolean active, boolean blocked,
            String createdAt, String lastLoginAt, int activeSessions
    ) { }

    public record AdminStatusChangeView(
            Long id, String createdAt, String changedBy, String userEmail, String accountNumber,
            String previousAccountStatus, String newAccountStatus,
            String previousUserStatus, String newUserStatus, String reason, String ipAddress
    ) { }
}
