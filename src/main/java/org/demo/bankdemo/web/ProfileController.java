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
import org.demo.bankdemo.profile.ProfileService;
import org.demo.bankdemo.profile.ProfileUpdateForm;
import org.demo.bankdemo.profile.TwoFactorService;

@Controller
@RequestMapping("/profile")
public class ProfileController {
    private final ProfileService profileService;
    private final TwoFactorService twoFactorService;
    private final ProfilePageModel pageModel;

    public ProfileController(ProfileService profileService, TwoFactorService twoFactorService, ProfilePageModel pageModel) {
        this.profileService = java.util.Objects.requireNonNull(profileService);
        this.twoFactorService = java.util.Objects.requireNonNull(twoFactorService);
        this.pageModel = java.util.Objects.requireNonNull(pageModel);
    }

    @GetMapping
    String view(Authentication authentication, Model model) {
        model.addAttribute("profile", profileService.profileFor(authentication.getName()));
        model.addAttribute("twoFactorEnabled", twoFactorService.isEnabled(authentication.getName()));
        pageModel.add(authentication, model);
        return "profile/view";
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
        pageModel.add(authentication, model);
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
            pageModel.add(authentication, model);
            return "profile/edit";
        }

        profileService.updateProfile(authentication.getName(), profileUpdateForm);
        redirectAttributes.addFlashAttribute(
                "profileSuccess",
                "Dane profilu zostały zaktualizowane."
        );
        return "redirect:/profile";
    }
}
