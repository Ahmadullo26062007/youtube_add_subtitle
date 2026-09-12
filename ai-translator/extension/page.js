// MAIN world. Content script (ISOLATED world) YouTube'ning `window` obyektini
// ko'ra olmaydi, shuning uchun caption tracklarni shu yerdan olib beramiz.
(() => {
  "use strict";

  const REQ = "yt-uz-request-tracks";
  const RES = "yt-uz-response-tracks";

  function fromPlayerResponse(response) {
    return (
      response
        ?.captions
        ?.playerCaptionsTracklistRenderer
        ?.captionTracks || null
    );
  }

  function getTracks() {
    // 1. Player elementi — SPA navigatsiyadan keyin ham eng ishonchli manba
    try {
      const player = document.querySelector("#movie_player");

      if (typeof player?.getPlayerResponse === "function") {
        const tracks = fromPlayerResponse(
          player.getPlayerResponse()
        );

        if (tracks?.length) return tracks;
      }
    } catch (e) {
      // ignore
    }

    // 2. ytd-watch-flexy komponentidagi cache
    try {
      const flexy = document.querySelector("ytd-watch-flexy");

      const tracks = fromPlayerResponse(flexy?.playerResponse);

      if (tracks?.length) return tracks;
    } catch (e) {
      // ignore
    }

    // 3. Sahifa yuklanganda inline yozilgan global
    try {
      const tracks = fromPlayerResponse(
        window.ytInitialPlayerResponse
      );

      if (tracks?.length) return tracks;
    } catch (e) {
      // ignore
    }

    return null;
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;

    const data = event.data;

    if (!data || data.source !== REQ) return;

    let tracks = null;
    let error = null;

    try {
      tracks = getTracks();
    } catch (e) {
      error = String(e && e.message || e);
    }

    window.postMessage(
      {
        source: RES,
        nonce: data.nonce,
        tracks,
        error
      },
      location.origin
    );
  });
})();
