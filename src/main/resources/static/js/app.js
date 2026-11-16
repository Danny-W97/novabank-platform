(() => {
    document.querySelectorAll("[data-copy-target]").forEach((button) => {
        button.addEventListener("click", async () => {
            const source = document.getElementById(button.dataset.copyTarget);
            if (!source) return;

            const text = source.textContent.trim();

            try {
                await navigator.clipboard.writeText(text.replace(/\s/g, ""));
            } catch {
                const input = document.createElement("textarea");
                input.value = text.replace(/\s/g, "");
                input.style.position = "fixed";
                input.style.opacity = "0";
                document.body.appendChild(input);
                input.select();
                document.execCommand("copy");
                input.remove();
            }

            const toastElement = document.getElementById("copyToast");
            if (toastElement && window.bootstrap) {
                bootstrap.Toast.getOrCreateInstance(toastElement, { delay: 2200 }).show();
            }
        });
    });

    document.querySelectorAll(".is-disabled").forEach((link) => {
        link.addEventListener("click", (event) => event.preventDefault());
    });

    document.querySelectorAll("form[data-confirm]").forEach((form) => {
        form.addEventListener("submit", (event) => {
            if (!window.confirm(form.dataset.confirm)) {
                event.preventDefault();
            }
        });
    });

    document.querySelectorAll("form[data-client-validation]").forEach((form) => {
        form.addEventListener("submit", (event) => {
            if (form.checkValidity()) {
                form.classList.add("was-validated");
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            form.classList.add("was-validated");
            form.querySelector(":invalid")?.focus();
        });
    });

    if (window.bootstrap) {
        document.querySelectorAll("[data-bs-toggle='tooltip']").forEach((element) => {
            bootstrap.Tooltip.getOrCreateInstance(element);
        });
    }

    const dashboard = document.querySelector("[data-dashboard]");
    if (dashboard) {
        const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        const skeleton = dashboard.querySelector("[data-dashboard-skeleton]");
        const periodSelect = dashboard.querySelector("#expensePeriod");

        if (!reducedMotion && skeleton) {
            dashboard.classList.add("is-loading");
            window.setTimeout(() => dashboard.classList.remove("is-loading"), 220);
        }

        periodSelect?.addEventListener("change", () => {
            const url = new URL(window.location.href);
            url.searchParams.set("period", periodSelect.value);
            if (reducedMotion) {
                window.location.assign(url);
                return;
            }
            dashboard.classList.add("is-loading");
            window.setTimeout(() => window.location.assign(url), 120);
        });
    }
})();
