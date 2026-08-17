(() => {
    const preloader = document.getElementById("nbPreloader");
    const brand = document.getElementById("nbPreloaderBrand");
    const skip = document.getElementById("nbPreloaderSkip");
    if (!preloader || !brand || !skip) return;

    const T = {
        shieldDraw: 150,
        shieldDrawDur: 950,
        tickDraw: 900,
        tickDrawDur: 550,
        wordmarkIn: 750,
        goldPour: 3000,
        taglineIn: 3650,
        reveal: 5650
    };
    let revealStarted = false;
    let goldTimer;
    let revealTimer;

    const applyDelays = () => {
        const shield = preloader.querySelector(".nb-preloader__shield");
        const tick = preloader.querySelector(".nb-preloader__tick");
        const wordmark = preloader.querySelector(".nb-preloader__wordmark");
        const tagline = preloader.querySelector(".nb-preloader__tagline");
        shield.style.animationDuration = `${T.shieldDrawDur}ms`;
        shield.style.animationDelay = `${T.shieldDraw}ms`;
        tick.style.animationDuration = `${T.tickDrawDur}ms`;
        tick.style.animationDelay = `${T.tickDraw}ms`;
        wordmark.style.animationDelay = `${T.wordmarkIn}ms`;
        tagline.style.animationDelay = `${T.taglineIn}ms`;
    };

    const reveal = () => {
        if (revealStarted) return;
        revealStarted = true;
        clearTimeout(goldTimer);
        clearTimeout(revealTimer);
        preloader.classList.add("nb-preloader--done");
        window.setTimeout(() => preloader.remove(), 800);
    };

    const play = () => {
        applyDelays();
        goldTimer = window.setTimeout(() => brand.classList.add("nb-preloader__brand--gold"), T.goldPour);
        revealTimer = window.setTimeout(reveal, T.reveal);
    };

    skip.addEventListener("click", () => {
        brand.classList.add("nb-preloader__brand--gold");
        reveal();
    });

    play();
})();
