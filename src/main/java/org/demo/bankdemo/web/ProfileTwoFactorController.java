package org.demo.bankdemo.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.RequestParam;
import org.demo.bankdemo.profile.TwoFactorException;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.profile.UserSessionService;

@Controller
@RequestMapping("/profile")
public class ProfileTwoFactorController {
    private final TwoFactorService twoFactorService;
    private final UserSessionService userSessionService;
    private final ProfilePageModel pageModel;

    public ProfileTwoFactorController(TwoFactorService twoFactorService, UserSessionService userSessionService, ProfilePageModel pageModel) {
        this.twoFactorService = java.util.Objects.requireNonNull(twoFactorService);
        this.userSessionService = java.util.Objects.requireNonNull(userSessionService);
        this.pageModel = java.util.Objects.requireNonNull(pageModel);
    }

    @GetMapping("/2fa")
    String twoFactor(Authentication authentication, HttpSession session, Model model) {
        var enabled = twoFactorService.isEnabled(authentication.getName());
        model.addAttribute("twoFactorEnabled", enabled);
        if (!enabled) {
            model.addAttribute("setup", twoFactorService.setupFor(authentication.getName(), session));
        }
        pageModel.add(authentication, model);
        return "profile/two-factor";
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
        pageModel.add(authentication, model);
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
}
