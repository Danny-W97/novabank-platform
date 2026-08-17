package org.demo.bankdemo.web;

import org.demo.bankdemo.admin.AdminService;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.auth.LoginFailureHandler;
import org.demo.bankdemo.auth.LoginSuccessHandler;
import org.demo.bankdemo.config.SecurityConfig;
import org.demo.bankdemo.config.PaymentApiProperties;
import org.demo.bankdemo.config.MetricsAccessProperties;
import org.demo.bankdemo.config.MetricsBasicAuthenticationFilter;
import org.demo.bankdemo.config.RetentionProperties;
import org.demo.bankdemo.currency.ExchangeService;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardSecurityService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.demo.bankdemo.dashboard.ExpenseChartService;
import org.demo.bankdemo.history.HistoryDetail;
import org.demo.bankdemo.history.HistoryExportService;
import org.demo.bankdemo.history.HistoryPage;
import org.demo.bankdemo.history.HistoryService;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.recipient.RecipientService;
import org.demo.bankdemo.retention.RetentionExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RecipientController.class, NotificationController.class, AdminController.class, FrontendController.class, HistoryController.class})
@Import({SecurityConfig.class, PaymentApiProperties.class, MetricsAccessProperties.class,
        MetricsBasicAuthenticationFilter.class,
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class})
class ExtensionWebTest {

    private static final String EMAIL = "admin@example.pl";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private RecipientService recipientService;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private AdminService adminService;
    @MockitoBean
    private OperationAuditService operationAuditService;
    @MockitoBean
    private ExchangeService exchangeService;
    @MockitoBean
    private ExpenseChartService expenseChartService;

    @MockitoBean
    private DashboardSecurityService dashboardSecurityService;

    @MockitoBean
    private HistoryService historyService;

    @MockitoBean
    private HistoryExportService historyExportService;

    @MockitoBean
    private LoginSuccessHandler loginSuccessHandler;

    @MockitoBean
    private LoginFailureHandler loginFailureHandler;

    @MockitoBean
    private RetentionExecutionService retentionExecutionService;

    @MockitoBean
    private RetentionProperties retentionProperties;

    @BeforeEach
    void setUp() {
        when(dashboardService.dashboardFor(EMAIL)).thenReturn(dashboard());
        when(recipientService.recipientsFor(EMAIL)).thenReturn(List.of());
        when(notificationService.notificationsFor(EMAIL))
                .thenReturn(new NotificationService.NotificationPage(List.of(), 0));
        when(adminService.dashboard())
                .thenReturn(new AdminService.AdminDashboard(2, 2, 5, List.of()));
        when(retentionExecutionService.recentRuns()).thenReturn(List.of());
        when(retentionProperties.getSchedule()).thenReturn("0 0 3 * * *");
        when(exchangeService.rates()).thenReturn(List.of());
        when(expenseChartService.expensesFor(EMAIL)).thenReturn(List.of());
        when(expenseChartService.expensesFor(EMAIL, 6)).thenReturn(List.of());
        when(dashboardSecurityService.forUser(EMAIL))
                .thenReturn(new DashboardSecurityService.DashboardSecurityView("Brak danych", true, true));
        when(historyService.historyFor(org.mockito.ArgumentMatchers.eq(EMAIL), any()))
                .thenReturn(new HistoryPage(List.of(), 0, 0, 0, 0, 0, false, false, List.of(), false));
        when(historyService.detailFor(EMAIL, 1L)).thenReturn(new HistoryDetail(
                1L, "TRX-1", "Przelew", "Zrealizowana", "Wychodząca", "Anna",
                "61 1090 1014 0000 0712 1981 2874", "12 1279 4313 8124 6631 4704 2900",
                "−10,00 zł", "Test", "27 lipca 2026, 08:00", "27 lipca 2026, 08:00"
        ));
    }

    @Test
    void rendersDashboardAndHistoryExtensions() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard").with(user(EMAIL)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/history").with(user(EMAIL)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/history/1").with(user(EMAIL)))
                .andExpect(status().isOk());
    }
    @Test
    void rendersRecipientsPage() throws Exception {
        mockMvc.perform(get("/recipients").with(user(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void rendersNotificationsPage() throws Exception {
        mockMvc.perform(get("/notifications").with(user(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void rendersAdminDashboardOnlyForAdminRole() throws Exception {
        mockMvc.perform(get("/admin").with(user(EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin").with(user(EMAIL).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rendersRetentionPanelOnlyForAdminRole() throws Exception {
        mockMvc.perform(get("/admin/data-retention").with(user(EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/data-retention").with(user(EMAIL).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRequestAccountBlock() throws Exception {
        mockMvc.perform(post("/admin/accounts/1/block")
                        .with(user(EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(adminService).blockAccount(eq(1L), eq(EMAIL), any());
    }

    @Test
    void adminCanRequestAccountClosure() throws Exception {
        mockMvc.perform(post("/admin/accounts/1/close")
                        .with(user(EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(adminService).closeAccount(eq(1L), eq(EMAIL), any());
    }

    private static DashboardView dashboard() {
        return new DashboardView(
                "Admin",
                "Admin Demo",
                "AD",
                EMAIL,
                "Nie podano",
                "NB-000001",
                "61 1090 1014 0000 0712 1981 2874",
                "10 000,00 zł",
                "Aktywne",
                "Poniedziałek, 27 lipca 2026",
                "lipiec",
                "Brak operacji",
                "−0,00 zł",
                0,
                "+0,00 zł",
                0,
                List.of()
        );
    }
}
