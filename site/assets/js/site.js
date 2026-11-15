// The page works without any of this: the recordings just don't play.
(function () {
  "use strict";

  var root = document.documentElement;

  // Light or dark, overriding the system preference once picked.
  var toggle = document.querySelector(".theme-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      var dark = root.dataset.theme
        ? root.dataset.theme === "dark"
        : matchMedia("(prefers-color-scheme: dark)").matches;
      root.dataset.theme = dark ? "light" : "dark";
      try { localStorage.setItem("theme", root.dataset.theme); } catch (e) {}
    });
  }

  function flash(button, text) {
    button.textContent = text;
    setTimeout(function () { button.textContent = "copy"; }, 1500);
  }

  document.addEventListener("click", function (event) {
    var copyButton = event.target.closest("[data-copy]");
    if (copyButton && navigator.clipboard) {
      navigator.clipboard.writeText(copyButton.dataset.copy).then(
        function () { flash(copyButton, "copied"); },
        function () { flash(copyButton, "couldn't copy"); });
    }
  });

  // The recordings, once the player's script, which comes after this one,
  // has run too. Without it, the page goes back to how it is without
  // JavaScript, saying why there's nothing to play.
  document.addEventListener("DOMContentLoaded", function () {
    if (!window.AsciinemaPlayer) {
      root.classList.remove("js");
      return;
    }

    // A recording gets its player the first time it's shown, once the
    // font the player measures its cells with is in, the windows' own.
    var mono = getComputedStyle(root).getPropertyValue("--mono").trim();
    var fonts = document.fonts.load("13px Iosevka").catch(function () {});
    var still = matchMedia("(prefers-reduced-motion: reduce)");

    function player(cast, autoPlay) {
      if (!cast.player) {
        cast.player = fonts.then(function () {
          cast.classList.add("loaded");
          return AsciinemaPlayer.create(cast.dataset.cast, cast, {
            theme: "neorepl",
            terminalFontFamily: mono,
            terminalLineHeight: 1.3,
            poster: cast.dataset.poster && "npt:" + cast.dataset.poster,
            // Without it the poster's frame stays blank till it plays.
            preload: true,
            autoPlay: autoPlay
          });
        });
      }
      return cast.player;
    }

    function pause(cast) {
      if (cast.player) cast.player.then(function (p) { p.pause(); });
    }

    // The ones in the text wait for a click, on the frame their poster
    // names.
    var inline = document.querySelectorAll(".text .cast");
    var seen = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        seen.unobserve(entry.target);
        player(entry.target, false);
      });
    }, { rootMargin: "200px" });
    inline.forEach(function (cast) { seen.observe(cast); });

    // The second window gets a copy of the window of every section marked
    // data-pane, which CSS shows, hiding the ones in the text, on wide
    // screens. wide has to say what main.css says.
    var wire = document.querySelector(".wire");
    var wide = matchMedia("(min-width: 1100px)");
    var sections = [];
    document.querySelectorAll("[data-pane]").forEach(function (section) {
      section.pane = document.createElement("div");
      section.pane.className = "pane";
      section.pane.appendChild(section.querySelector(":scope > .wire-figure").cloneNode(true));
      wire.appendChild(section.pane);
      sections.push(section);
    });

    // The pane of the section being read plays its recording, unless
    // motion is to be kept down, once it's been up for a moment, so that
    // scrolling past a section doesn't start one.
    var current = null;
    var starting = null;
    function hide() {
      clearTimeout(starting);
      if (!current) return;
      current.classList.remove("active");
      pause(current.querySelector(".cast"));
      current = null;
    }
    function show(pane) {
      if (pane === current) return;
      hide();
      pane.classList.add("active");
      current = pane;
      starting = setTimeout(function () {
        var cast = pane.querySelector(".cast");
        var started = cast.player;
        var playing = player(cast, !still.matches);
        if (started && !still.matches) playing.then(function (p) { p.play(); });
      }, 250);
    }

    // The section being read is the last one whose top is past a line
    // about a quarter of the way down the screen. Sections without a pane
    // of their own (the news, say) keep the one before them.
    function follow() {
      if (!wide.matches) return hide();
      var line = window.innerHeight * 0.25;
      var reading = sections[0];
      sections.forEach(function (section) {
        if (section.getBoundingClientRect().top <= line) reading = section;
      });
      show(reading.pane);
    }
    var scheduled = false;
    window.addEventListener("scroll", function () {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(function () { scheduled = false; follow(); });
    }, { passive: true });
    // Widening hides the ones in the text, which then stop playing.
    wide.addEventListener("change", function () {
      if (wide.matches) inline.forEach(pause);
      follow();
    });
    follow();
  });
})();
