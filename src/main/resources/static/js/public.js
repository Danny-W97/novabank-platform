(() => {
    const url = new URL(window.location.href);
    if (url.searchParams.has("logout")) {
        url.searchParams.delete("logout");
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
    }

    const canvas = document.getElementById("starfield");

    if (canvas) {
        const context = canvas.getContext("2d");
        const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        let stars = [];
        let animationFrame;

        const resize = () => {
            const ratio = Math.min(window.devicePixelRatio || 1, 2);
            canvas.width = window.innerWidth * ratio;
            canvas.height = window.innerHeight * ratio;
            canvas.style.width = `${window.innerWidth}px`;
            canvas.style.height = `${window.innerHeight}px`;
            context.setTransform(ratio, 0, 0, ratio, 0, 0);

            const count = Math.max(55, Math.floor((window.innerWidth * window.innerHeight) / 12500));
            stars = Array.from({ length: count }, () => ({
                x: Math.random() * window.innerWidth,
                y: Math.random() * window.innerHeight,
                radius: Math.random() * 1.4 + 0.25,
                alpha: Math.random() * 0.6 + 0.25,
                speed: Math.random() * 0.008 + 0.002,
                phase: Math.random() * Math.PI * 2
            }));
        };

        const draw = (time = 0) => {
            context.clearRect(0, 0, window.innerWidth, window.innerHeight);
            stars.forEach((star) => {
                const alpha = reduceMotion
                    ? star.alpha
                    : star.alpha * (0.68 + Math.sin(time * star.speed + star.phase) * 0.32);
                context.beginPath();
                context.fillStyle = `rgba(207, 225, 255, ${alpha})`;
                context.arc(star.x, star.y, star.radius, 0, Math.PI * 2);
                context.fill();
            });

            if (!reduceMotion) {
                animationFrame = requestAnimationFrame(draw);
            }
        };

        resize();
        draw();
        window.addEventListener("resize", () => {
            cancelAnimationFrame(animationFrame);
            resize();
            draw();
        });
    }

    document.querySelectorAll("[data-password-toggle]").forEach((button) => {
        button.addEventListener("click", () => {
            const input = document.getElementById(button.dataset.passwordToggle);
            const icon = button.querySelector("i");
            const showingPassword = input.type === "text";

            input.type = showingPassword ? "password" : "text";
            icon.className = showingPassword ? "bi bi-eye" : "bi bi-eye-slash";
            button.setAttribute("aria-label", showingPassword ? "Pokaż hasło" : "Ukryj hasło");
        });
    });
})();
