package org.demo.bankdemo.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.profile.PasswordChangeForm;
import org.demo.bankdemo.profile.ProfileException;
import org.demo.bankdemo.profile.ProfileService;
import org.demo.bankdemo.profile.TransactionPinForm;
import org.demo.bankdemo.profile.TransactionPinService;

@Controller
@RequestMapping("/profile")
public class ProfileSecurityController {
    private final ProfileService profileService;
    private final TransactionPinService transactionPinService;
    private final ProfilePageModel pageModel;

    public ProfileSecurityController(ProfileService profileService, TransactionPinService transactionPinService, ProfilePageModel pageModel) {
        this.profileService = java.util.Objects.requireNonNull(profileService);
        this.transactionPinService = java.util.Objects.requireNonNull(transactionPinService);
        this.pageModel = java.util.Objects.requireNonNull(pageModel);
    }

    @GetMapping("/password")
    String password(Authentication authentication, Model model) {
        if (!model.containsAttribute("passwordChangeForm")) {
            model.addAttribute("passwordChangeForm", new PasswordChangeForm());
        }
        pageModel.add(authentication, model);
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
            pageModel.add(authentication, model);
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
        pageModel.add(authentication, model);
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
            pageModel.add(authentication, model);
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
}
