package org.demo.bankdemo.web;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.profile.PasswordChangeForm;
import org.demo.bankdemo.profile.ProfileException;
import org.demo.bankdemo.profile.ProfileService;
import org.demo.bankdemo.profile.ProfileUpdateForm;
import org.demo.bankdemo.profile.TransactionPinForm;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.profile.TwoFactorException;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.profile.UserLimitsForm;
import org.demo.bankdemo.profile.UserLimitsService;
import org.demo.bankdemo.profile.UserSessionService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
public class ProfileController {

    private final DashboardService dashboardService;
    private final ProfileService profileService;
    private final UserSessionService userSessionService;
    private final TransactionPinService transactionPinService;
    private final TwoFactorService twoFactorService;
    private final UserLimitsService userLimitsService;

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileController(
            DashboardService dashboardService,
            ProfileService profileService,
            UserSessionService userSessionService,
            TransactionPinService transactionPinService,
            TwoFactorService twoFactorService,
            UserLimitsService userLimitsService
    ) {
        this.dashboardService = dashboardService;
        this.profileService = profileService;
        this.userSessionService = userSessionService;
        this.transactionPinService = transactionPinService;
        this.twoFactorService = twoFactorService;
        this.userLimitsService = userLimitsService;
    }

    public ProfileController(
            DashboardService dashboardService,
            ProfileService profileService,
            UserSessionService userSessionService,
            TransactionPinService transactionPinService,
            TwoFactorService twoFactorService
    ) {
        this(dashboardService, profileService, userSessionService,
                transactionPinService, twoFactorService, null);
    }

    @GetMapping
    String view(Authentication authentication, Model model) {
        model.addAttribute("profile", profileService.profileFor(authentication.getName()));
        model.addAttribute("twoFactorEnabled", twoFactorService.isEnabled(authentication.getName()));
        addCommonModel(authentication, model);
        return "profile/view";
    }

    @GetMapping("/2fa")
    String twoFactor(Authentication authentication, HttpSession session, Model model) {
        var enabled = twoFactorService.isEnabled(authentication.getName());
        model.addAttribute("twoFactorEnabled", enabled);
        if (!enabled) {
            model.addAttribute("setup", twoFactorService.setupFor(authentication.getName(), session));
        }
        addCommonModel(authentication, model);
        return "profile/two-factor";
    }

    @GetMapping("/limits")
    String limits(Authentication authentication, Model model) {
        if (!model.containsAttribute("userLimitsForm")) {
            model.addAttribute("userLimitsForm", userLimitsService.formFor(authentication.getName()));
        }
        model.addAttribute("userLimits", userLimitsService.limitsFor(authentication.getName()));
        model.addAttribute("twoFactorEnabled", twoFactorService.isEnabled(authentication.getName()));
        addCommonModel(authentication, model);
        return "profile/limits";
    }

    @PostMapping("/limits")
    String updateLimits(
            Authentication authentication,
            @Valid @ModelAttribute("userLimitsForm") UserLimitsForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                userLimitsService.update(authentication.getName(), form);
            } catch (ProfileException exception) {
                bindingResult.rejectValue(exception.getField(), "limits.invalid", exception.getMessage());
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("userLimits", userLimitsService.limitsFor(authentication.getName()));
            model.addAttribute("twoFactorEnabled", twoFactorService.isEnabled(authentication.getName()));
            addCommonModel(authentication, model);
            return "profile/limits";
        }
        redirectAttributes.addFlashAttribute("profileSuccess", "Limity przelewow, platnosci, karty i prog TOTP zostaly zaktualizowane.");
        return "redirect:/profile";
    }

    @PostMapping("/2fa/enable")
    String enableTwoFactor(
            Authentication authentication,
            @RequestParam(value = "code", required = false) String code,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            var recoveryCodes = twoFactorService.confirmSetup(authentication.getName(), code, session);
            session.setAttribute("BANK_TOTP_RECOVERY_CODES", recoveryCodes);
            return "redirect:/profile/2fa/recovery";
        } catch (TwoFactorException exception) {
            redirectAttributes.addFlashAttribute("twoFactorError", exception.getMessage());
            return "redirect:/profile/2fa";
        }
    }

    @GetMapping("/2fa/recovery")
    String recoveryCodes(Authentication authentication, HttpSession session, Model model) {
        var codes = session.getAttribute("BANK_TOTP_RECOVERY_CODES");
        if (codes == null) {
            return "redirect:/profile";
        }
        session.removeAttribute("BANK_TOTP_RECOVERY_CODES");
        model.addAttribute("recoveryCodes", codes);
        addCommonModel(authentication, model);
        return "profile/two-factor-recovery";
    }

    @PostMapping("/2fa/disable")
    String disableTwoFactor(
            Authentication authentication,
            @RequestParam(value = "currentPassword", required = false) String currentPassword,
            @RequestParam(value = "code", required = false) String code,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            twoFactorService.disable(authentication.getName(), currentPassword, code);
            userSessionService.invalidateOtherSessions(authentication.getName(), session.getId());
            redirectAttributes.addFlashAttribute("profileSuccess", "Uwierzytelnianie 2FA zostało wyłączone.");
        } catch (TwoFactorException exception) {
            redirectAttributes.addFlashAttribute("twoFactorError", exception.getMessage());
        }
        return "redirect:/profile/2fa";
    }

    @GetMapping("/edit")
    String edit(Authentication authentication, Model model) {
        if (!model.containsAttribute("profileUpdateForm")) {
            model.addAttribute(
                    "profileUpdateForm",
                    profileService.editFormFor(authentication.getName())
            );
        }
        model.addAttribute("profile", profileService.profileFor(authentication.getName()));
        addCommonModel(authentication, model);
        return "profile/edit";
    }

    @PostMapping("/edit")
    String update(
            Authentication authentication,
            @Valid @ModelAttribute ProfileUpdateForm profileUpdateForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("profile", profileService.profileFor(authentication.getName()));
            addCommonModel(authentication, model);
            return "profile/edit";
        }

        profileService.updateProfile(authentication.getName(), profileUpdateForm);
        redirectAttributes.addFlashAttribute(
                "profileSuccess",
                "Dane profilu zostały zaktualizowane."
        );
        return "redirect:/profile";
    }

    @GetMapping("/password")
    String password(Authentication authentication, Model model) {
        if (!model.containsAttribute("passwordChangeForm")) {
            model.addAttribute("passwordChangeForm", new PasswordChangeForm());
        }
        addCommonModel(authentication, model);
        return "profile/password";
    }

    @PostMapping("/password")
    String changePassword(
            Authentication authentication,
            @Valid @ModelAttribute PasswordChangeForm passwordChangeForm,
            BindingResult bindingResult,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!passwordChangeForm.passwordsMatch()) {
            bindingResult.rejectValue(
                    "passwordConfirmation",
                    "password.mismatch",
                    "Podane nowe hasła nie są zgodne."
            );
        }

        if (!bindingResult.hasErrors()) {
            try {
                profileService.changePassword(
                        authentication.getName(),
                        passwordChangeForm.getCurrentPassword(),
                        passwordChangeForm.getNewPassword()
                );
            } catch (ProfileException exception) {
                bindingResult.rejectValue(
                        exception.getField(),
                        "password.invalid",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            addCommonModel(authentication, model);
            return "profile/password";
        }

        session.invalidate();
        return "redirect:/login?passwordChanged";
    }

    @GetMapping("/pin")
    String pin(Authentication authentication, Model model) {
        if (!model.containsAttribute("transactionPinForm")) {
            model.addAttribute("transactionPinForm", new TransactionPinForm());
        }
        model.addAttribute("pinConfigured", transactionPinService.isConfigured(authentication.getName()));
        addCommonModel(authentication, model);
        return "profile/pin";
    }

    @PostMapping("/pin")
    String changePin(
            Authentication authentication,
            @Valid @ModelAttribute TransactionPinForm transactionPinForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!transactionPinForm.pinsMatch()) {
            bindingResult.rejectValue("pinConfirmation", "pin.mismatch", "Podane PIN-y nie są zgodne.");
        }
        if (!bindingResult.hasErrors()) {
            try {
                transactionPinService.setPin(
                        authentication.getName(),
                        transactionPinForm.getCurrentPassword(),
                        transactionPinForm.getPin()
                );
            } catch (ProfileException exception) {
                bindingResult.rejectValue(exception.getField(), "pin.invalid", exception.getMessage());
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("pinConfigured", transactionPinService.isConfigured(authentication.getName()));
            addCommonModel(authentication, model);
            return "profile/pin";
        }
        redirectAttributes.addFlashAttribute("profileSuccess", "PIN transakcyjny został zapisany.");
        return "redirect:/profile";
    }

    @PostMapping("/pin/remove")
    String removePin(
            Authentication authentication,
            @ModelAttribute TransactionPinForm transactionPinForm,
            RedirectAttributes redirectAttributes
    ) {
        try {
            transactionPinService.removePin(
                    authentication.getName(),
                    transactionPinForm.getCurrentPassword()
            );
            redirectAttributes.addFlashAttribute("profileSuccess", "PIN transakcyjny został wyłączony.");
            return "redirect:/profile";
        } catch (ProfileException exception) {
            redirectAttributes.addFlashAttribute("pinError", exception.getMessage());
            return "redirect:/profile/pin";
        }
    }

    private void addCommonModel(Authentication authentication, Model model) {
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }
}
