package org.demo.bankdemo.web;

import org.demo.bankdemo.auth.LoginFailureHandler;
import org.demo.bankdemo.auth.LoginSuccessHandler;
import org.demo.bankdemo.config.SecurityConfig;
import org.demo.bankdemo.config.PaymentApiProperties;
import org.demo.bankdemo.config.MetricsAccessProperties;
import org.demo.bankdemo.config.MetricsBasicAuthenticationFilter;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.demo.bankdemo.profile.ProfileService;
import org.demo.bankdemo.profile.ProfileUpdateForm;
import org.demo.bankdemo.profile.ProfileView;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.profile.UserLimitsService;
import org.demo.bankdemo.profile.UserSessionService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProfileController.class)
@Import({SecurityConfig.class, PaymentApiProperties.class, MetricsAccessProperties.class,
        MetricsBasicAuthenticationFilter.class,
        SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class})
class ProfileSecurityWebTest {

    private static final String EMAIL = "anna.nowak@example.pl";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private UserSessionService userSessionService;
    @MockitoBean
    private TransactionPinService transactionPinService;

    @MockitoBean
    private TwoFactorService twoFactorService;

    @MockitoBean
    private UserLimitsService userLimitsService;

    @MockitoBean
    private LoginSuccessHandler loginSuccessHandler;

    @MockitoBean
    private LoginFailureHandler loginFailureHandler;

    @BeforeEach
    void setUp() {
        when(dashboardService.dashboardFor(EMAIL)).thenReturn(dashboardView());
        when(profileService.profileFor(EMAIL)).thenReturn(new ProfileView(
                "Anna",
                "Nowak",
                EMAIL,
                "+48 500 600 700",
                "12 kwietnia 1995",
                "26 lipca 2026, 16:00"
        ));
        var editForm = new ProfileUpdateForm();
        editForm.setFirstName("Anna");
        editForm.setLastName("Nowak");
        editForm.setPhone("+48 500 600 700");
        when(profileService.editFormFor(EMAIL)).thenReturn(editForm);
    }

    @Test
    void redirectsAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void rendersProfileForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/profile").with(user(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void rendersProfileEditFormForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/profile/edit").with(user(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void rendersPasswordFormForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/profile/password").with(user(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    void redirectsToLoginAfterPasswordChange() throws Exception {
        mockMvc.perform(post("/profile/password")
                        .param("currentPassword", "Current1")
                        .param("newPassword", "NewSecure2")
                        .param("passwordConfirmation", "NewSecure2")
                        .with(user(EMAIL))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?passwordChanged"));

        verify(profileService).changePassword(eq(EMAIL), eq("Current1"), eq("NewSecure2"));
    }
    @Test
    void rendersTransactionPinFormForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/profile/pin").with(user(EMAIL)))
                .andExpect(status().isOk());
    }
    @Test
    void deniesAdminAreaToRegularUser() throws Exception {
        mockMvc.perform(get("/admin").with(user(EMAIL).roles("USER")))
                .andExpect(status().isForbidden());
    }
    @Test
    void rejectsProfileUpdateWithoutCsrfToken() throws Exception {
        mockMvc.perform(validProfileUpdate().with(user(EMAIL)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsValidProfileUpdateWithCsrfToken() throws Exception {
        mockMvc.perform(validProfileUpdate().with(user(EMAIL)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(profileService).updateProfile(eq(EMAIL), any(ProfileUpdateForm.class));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    validProfileUpdate() {
        return post("/profile/edit")
                .param("firstName", "Anna")
                .param("lastName", "Nowak")
                .param("phone", "+48 500 600 700")
                .param("birthDate", "1995-04-12");
    }

    private static DashboardView dashboardView() {
        return new DashboardView(
                "Anna",
                "Anna Nowak",
                "AN",
                EMAIL,
                "+48 500 600 700",
                "NB-000007",
                "61 1090 1014 0000 0712 1981 2874",
                "10 000,00 zł",
                "Aktywne",
                "Niedziela, 26 lipca 2026",
                "lipiec",
                "Brak operacji",
                "−0,00 zł",
                0,
                "+10 000,00 zł",
                1,
                List.of()
        );
    }
}
