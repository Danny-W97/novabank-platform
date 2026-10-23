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
import org.demo.bankdemo.profile.ProfileException;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.profile.UserLimitsForm;
import org.demo.bankdemo.profile.UserLimitsService;

@Controller
@RequestMapping("/profile")
public class ProfileLimitsController {
    private final UserLimitsService userLimitsService;
    private final TwoFactorService twoFactorService;
    private final ProfilePageModel pageModel;

    public ProfileLimitsController(UserLimitsService userLimitsService, TwoFactorService twoFactorService, ProfilePageModel pageModel) {
        this.userLimitsService = java.util.Objects.requireNonNull(userLimitsService);
        this.twoFactorService = java.util.Objects.requireNonNull(twoFactorService);
        this.pageModel = java.util.Objects.requireNonNull(pageModel);
    }

    @GetMapping("/limits")
    String limits(Authentication authentication, Model model) {
        if (!model.containsAttribute("userLimitsForm")) {
            model.addAttribute("userLimitsForm", userLimitsService.formFor(authentication.getName()));
        }
        model.addAttribute("userLimits", userLimitsService.limitsFor(authentication.getName()));
        model.addAttribute("twoFactorEnabled", twoFactorService.isEnabled(authentication.getName()));
        pageModel.add(authentication, model);
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
            pageModel.add(authentication, model);
            return "profile/limits";
        }
        redirectAttributes.addFlashAttribute("profileSuccess", "Limity przelewow, platnosci, karty i prog TOTP zostaly zaktualizowane.");
        return "redirect:/profile";
    }
}
