package org.demo.bankdemo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.config.EmailProperties;
import org.demo.bankdemo.profile.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.web.csrf.CsrfToken;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class AuthController {

    private static final String VERIFICATION_DELIVERY_ERROR_MESSAGE =
            "Nie udało się wysłać wiadomości e-mail z linkiem aktywacyjnym (serwer pocztowy SMTP jest niedostępny). "
                    + "Konto zostało utworzone i oczekuje na potwierdzenie — upewnij się, że usługa pocztowa (w DEV: Mailpit) działa, "
                    + "a następnie kliknij „Wyślij ponownie”.";
    private static final String RESET_DELIVERY_ERROR_MESSAGE =
            "Nie udało się wysłać wiadomości e-mail z instrukcją resetu hasła (serwer pocztowy SMTP jest niedostępny). "
                    + "Spróbuj ponownie za chwilę.";

    private final RegistrationService registrationService;
    private final TwoFactorService twoFactorService;
    private final TwoFactorLoginService twoFactorLoginService;
    private final EmailTokenService emailTokenService;
    private final LoginHistoryService loginHistoryService;
    private final EmailProperties emailProperties;
    private final Environment environment;

    @Autowired
    public AuthController(
            RegistrationService registrationService,
            TwoFactorService twoFactorService,
            TwoFactorLoginService twoFactorLoginService,
            EmailTokenService emailTokenService,
            LoginHistoryService loginHistoryService,
            EmailProperties emailProperties,
            Environment environment
    ) {
        this.registrationService = registrationService;
        this.twoFactorService = twoFactorService;
        this.twoFactorLoginService = twoFactorLoginService;
        this.emailTokenService = emailTokenService;
        this.loginHistoryService = loginHistoryService;
        this.emailProperties = emailProperties;
        this.environment = environment;
    }

    public AuthController(
            RegistrationService registrationService,
            TwoFactorService twoFactorService,
            TwoFactorLoginService twoFactorLoginService,
            EmailTokenService emailTokenService,
            LoginHistoryService loginHistoryService
    ) {
        this(registrationService, twoFactorService, twoFactorLoginService, emailTokenService,
                loginHistoryService, new EmailProperties(), null);
    }

    @GetMapping("/login/2fa")
    String twoFactorForm(HttpSession session, Model model) {
        var pending = twoFactorLoginService.pending(session);
        if (pending.isEmpty()) {
            return "redirect:/login?error";
        }
        model.addAttribute("twoFactorEmail", pending.get().email());
        return "auth/two-factor";
    }

    @PostMapping("/login/2fa")
    String verifyTwoFactor(
            @RequestParam(value = "code", required = false) String code,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session
    ) {
        var pending = twoFactorLoginService.pending(session);
        if (pending.isEmpty()) {
            return "redirect:/login?error";
        }
        if (!twoFactorService.verifyLoginCode(pending.get().email(), code)) {
            loginHistoryService.record(pending.get().email(), request, false, "Nieprawidłowy kod 2FA");
            return twoFactorLoginService.recordFailure(session)
                    ? "redirect:/login/2fa?error"
                    : "redirect:/login?error";
        }
        try {
            var target = twoFactorLoginService.complete(request, response, session, pending.get().challengeId());
            return "redirect:" + target;
        } catch (org.springframework.security.core.AuthenticationException | IllegalStateException exception) {
            twoFactorLoginService.clear(session);
            loginHistoryService.record(pending.get().email(), request, false, "Nie można zakończyć wyzwania 2FA.");
            return "redirect:/login?error";
        }
    }

    @GetMapping("/login")
    String login(
            @RequestParam(value = "continue", required = false) String continueUrl,
            @RequestParam(value = "logout", required = false) String logout,
            Model model,
            HttpServletRequest request
    ) {
        // Resolve the deferred CSRF token before Thymeleaf starts writing the large preloader.
        // Otherwise the response buffer can be committed before Spring Session creates the token.
        var csrf = request.getAttribute("_csrf");
        if (csrf instanceof CsrfToken token) {
            token.getToken();
        }
        model.addAttribute("continueUrl", continueUrl);
        model.addAttribute("showPreloader", logout == null);
        return "auth/login";
    }

    @GetMapping("/register")
    String registrationForm(Model model) {
        if (!model.containsAttribute("registrationForm")) {
            model.addAttribute("registrationForm", new RegistrationForm());
        }
        return "auth/register";
    }

    @PostMapping("/register")
    String register(
            @Valid @ModelAttribute RegistrationForm registrationForm,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (!registrationForm.passwordsMatch()) {
            bindingResult.rejectValue(
                    "passwordConfirmation",
                    "password.mismatch",
                    "Podane hasła nie są zgodne."
            );
        }

        if (bindingResult.hasErrors()) {
            return "auth/register";
        }

        try {
            registrationService.register(registrationForm);
        } catch (DuplicateEmailException exception) {
            bindingResult.rejectValue(
                    "email",
                    "email.unavailable",
                    "Nie można utworzyć konta dla podanego adresu e-mail."
            );
            return "auth/register";
        } catch (EmailDeliveryException exception) {
            redirectAttributes.addFlashAttribute(
                    "verificationDeliveryError",
                    VERIFICATION_DELIVERY_ERROR_MESSAGE
            );
        }

        return "redirect:/verify-email/sent?email="
                + URLEncoder.encode(registrationForm.getEmail().strip(), StandardCharsets.UTF_8);
    }

    @GetMapping("/verify-email/sent")
    String verificationSent(
            @RequestParam(value = "email", required = false, defaultValue = "") String email,
            Model model
    ) {
        model.addAttribute("email", email);
        if (isDevMailpitVisible()) {
            model.addAttribute("mailpitUrl", emailProperties.getMailpitUrl());
        }
        return "auth/check-email";
    }

    @PostMapping("/verify-email/resend")
    String resendVerification(
            @RequestParam(value = "email", required = false, defaultValue = "") String email,
            RedirectAttributes redirectAttributes
    ) {
        try {
            emailTokenService.resendVerification(email);
            redirectAttributes.addFlashAttribute(
                    "verificationSent",
                    "Jeśli konto wymaga potwierdzenia, wysłaliśmy nowy link."
            );
        } catch (EmailDeliveryException exception) {
            redirectAttributes.addFlashAttribute(
                    "verificationDeliveryError",
                    VERIFICATION_DELIVERY_ERROR_MESSAGE
            );
        }
        return "redirect:/verify-email/sent?email="
                + URLEncoder.encode(email.strip(), StandardCharsets.UTF_8);
    }

    private boolean isDevMailpitVisible() {
        if (emailProperties == null
                || emailProperties.getMailpitUrl() == null
                || emailProperties.getMailpitUrl().isBlank()) {
            return false;
        }
        if (environment == null) {
            return false;
        }
        return environment.acceptsProfiles(Profiles.of("dev"))
                && !environment.acceptsProfiles(Profiles.of("prod"));
    }

    @GetMapping("/verify-email")
    String verifyEmail(
            @RequestParam(value = "token", required = false, defaultValue = "") String token,
            RedirectAttributes redirectAttributes
    ) {
        try {
            emailTokenService.verifyEmail(token);
            redirectAttributes.addFlashAttribute(
                    "emailVerified",
                    "Adres e-mail został potwierdzony. Możesz się zalogować."
            );
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("emailVerificationError", exception.getMessage());
        }
        return "redirect:/login";
    }

    @GetMapping("/password/forgot")
    String forgotPassword() {
        return "auth/forgot-password";
    }

    @PostMapping("/password/forgot")
    String requestPasswordReset(
            @RequestParam(value = "email", required = false, defaultValue = "") String email,
            RedirectAttributes redirectAttributes
    ) {
        try {
            emailTokenService.requestPasswordReset(email);
            redirectAttributes.addFlashAttribute(
                    "resetRequested",
                    "Jeśli podany adres istnieje, wysłaliśmy instrukcję resetowania hasła."
            );
        } catch (EmailDeliveryException exception) {
            redirectAttributes.addFlashAttribute(
                    "resetDeliveryError",
                    RESET_DELIVERY_ERROR_MESSAGE
            );
        }
        return "redirect:/password/forgot";
    }

    @GetMapping("/password/reset")
    String resetPasswordForm(
            @RequestParam(value = "token", required = false, defaultValue = "") String token,
            Model model
    ) {
        if (token.isBlank()) {
            return "redirect:/password/forgot";
        }
        model.addAttribute("token", token);
        if (!model.containsAttribute("passwordResetForm")) {
            model.addAttribute("passwordResetForm", new PasswordResetForm());
        }
        return "auth/reset-password";
    }

    @PostMapping("/password/reset")
    String resetPassword(
            @RequestParam("token") String token,
            @Valid @ModelAttribute PasswordResetForm passwordResetForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!passwordResetForm.passwordsMatch()) {
            bindingResult.rejectValue(
                    "passwordConfirmation",
                    "password.mismatch",
                    "Podane hasła nie są zgodne."
            );
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("token", token);
            return "auth/reset-password";
        }
        try {
            emailTokenService.resetPassword(token, passwordResetForm.getNewPassword());
        } catch (IllegalArgumentException exception) {
            model.addAttribute("token", token);
            model.addAttribute("resetError", exception.getMessage());
            return "auth/reset-password";
        }
        redirectAttributes.addFlashAttribute(
                "passwordReset",
                "Hasło zostało zmienione. Możesz się zalogować."
        );
        return "redirect:/login";
    }
}
