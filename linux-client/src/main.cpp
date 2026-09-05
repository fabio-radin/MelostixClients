// Melostix - Linux client (SDL2 + SDL_ttf per il testo dei testi, Dear ImGui gia'
// agganciato per un pannellino di stato/impostazioni - vedi README per come estenderlo).

#include <SDL.h>
#include <SDL_ttf.h>
#include <imgui.h>
#include <imgui_impl_sdl2.h>
#include <imgui_impl_sdlrenderer2.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "net_client.h"

namespace {

const std::vector<std::string> kFontCandidates = {
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/TTF/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
};

const SDL_Color kWhite{255, 255, 255, 255};
const SDL_Color kDim{158, 158, 158, 255};
const SDL_Color kError{255, 110, 110, 255};

TTF_Font* openFirstAvailableFont(const std::string& fontOverride, int size) {
    if (!fontOverride.empty()) {
        TTF_Font* font = TTF_OpenFont(fontOverride.c_str(), size);
        if (font) return font;
        std::fprintf(stderr, "impossibile aprire il font indicato '%s': %s\n", fontOverride.c_str(), TTF_GetError());
    }
    for (const auto& path : kFontCandidates) {
        TTF_Font* font = TTF_OpenFont(path.c_str(), size);
        if (font) return font;
    }
    return nullptr;
}

/** Disegna una riga di testo centrata orizzontalmente su centerX, con l'alto del testo a y. */
void renderCenteredText(SDL_Renderer* renderer, TTF_Font* font, const std::string& text,
                         int centerX, int y, SDL_Color color) {
    if (!font || text.empty()) return;
    SDL_Surface* surface = TTF_RenderUTF8_Blended(font, text.c_str(), color);
    if (!surface) return;
    SDL_Texture* texture = SDL_CreateTextureFromSurface(renderer, surface);
    if (texture) {
        SDL_Rect dst{centerX - surface->w / 2, y, surface->w, surface->h};
        SDL_RenderCopy(renderer, texture, nullptr, &dst);
        SDL_DestroyTexture(texture);
    }
    SDL_FreeSurface(surface);
}

void drawLyrics(SDL_Renderer* renderer, TTF_Font* fontCurrent, TTF_Font* fontDim,
                 const lyrics::StateSnapshot& snap, int width, int height) {
    const int centerX = width / 2;
    const int midY = height / 2;

    if (!snap.connected) {
        renderCenteredText(renderer, fontCurrent, snap.info, centerX, midY - 18, kDim);
        return;
    }

    if (snap.status == "synced") {
        renderCenteredText(renderer, fontDim, snap.previous, centerX, midY - 64, kDim);
        renderCenteredText(renderer, fontCurrent, snap.current, centerX, midY - 20, kWhite);
        renderCenteredText(renderer, fontDim, snap.next, centerX, midY + 46, kDim);
    } else if (snap.status == "loading") {
        renderCenteredText(renderer, fontCurrent, "Searching lyrics for \"" + snap.title + "\"...",
                            centerX, midY - 18, kWhite);
    } else if (snap.status == "plain") {
        renderCenteredText(renderer, fontCurrent, snap.title + " - " + snap.artist, centerX, midY - 40, kWhite);
        renderCenteredText(renderer, fontDim, "(lyrics not synced)", centerX, midY + 12, kDim);
    } else if (snap.status == "not_found") {
        renderCenteredText(renderer, fontCurrent, "Lyrics not found for \"" + snap.title + "\"",
                            centerX, midY - 18, kError);
    } else if (snap.status == "error") {
        renderCenteredText(renderer, fontCurrent, "Lyrics search error", centerX, midY - 18, kError);
    } else {  // "none"
        std::string text = snap.title.empty() ? "No track playing" : (snap.title + " - " + snap.artist);
        renderCenteredText(renderer, fontCurrent, text, centerX, midY - 18, kWhite);
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::string fixedHost;
    int fixedPort = 8420;
    std::string fontOverride;
    std::string password;
    for (int i = 1; i < argc; i++) {
        std::string arg = argv[i];
        if (arg == "--host" && i + 1 < argc) {
            fixedHost = argv[++i];
        } else if (arg == "--port" && i + 1 < argc) {
            fixedPort = std::atoi(argv[++i]);
        } else if (arg == "--font" && i + 1 < argc) {
            fontOverride = argv[++i];
        } else if (arg == "--password" && i + 1 < argc) {
            // Password condivisa opzionale per l'handshake di autenticazione del master
            // (protocollo 1.1.0, MelostixProtocol) - omessa se il master non ne richiede una.
            password = argv[++i];
        }
    }

    lyrics::SharedState state;
    std::atomic<bool> stopFlag{false};
    std::thread netThread(lyrics::runNetworkClient, std::ref(state), std::ref(stopFlag), fixedHost, fixedPort, password);

    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        std::fprintf(stderr, "SDL_Init fallito: %s\n", SDL_GetError());
        stopFlag.store(true);
        netThread.join();
        return 1;
    }
    if (TTF_Init() != 0) {
        std::fprintf(stderr, "TTF_Init fallito: %s\n", TTF_GetError());
        stopFlag.store(true);
        netThread.join();
        SDL_Quit();
        return 1;
    }

    SDL_Window* window = SDL_CreateWindow(
        "Melostix - Linux Client",
        SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        1024, 600,
        SDL_WINDOW_RESIZABLE);
    SDL_Renderer* renderer = SDL_CreateRenderer(
        window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);

    // Due dimensioni, come le due righe dim/current del client Python e delle glasses.
    TTF_Font* fontCurrent = openFirstAvailableFont(fontOverride, 36);
    TTF_Font* fontDim = openFirstAvailableFont(fontOverride, 26);
    if (!fontCurrent || !fontDim) {
        std::fprintf(
            stderr,
            "Nessun font di sistema trovato (provato DejaVu Sans/Liberation/Noto). "
            "Installa 'fonts-dejavu-core' o passa --font <percorso.ttf>. "
            "L'app continua comunque (il pannello Dear ImGui funziona senza font TTF).\n");
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::StyleColorsDark();
    ImGui_ImplSDL2_InitForSDLRenderer(window, renderer);
    ImGui_ImplSDLRenderer2_Init(renderer);

    bool showOverlay = true;
    bool running = true;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            ImGui_ImplSDL2_ProcessEvent(&event);
            if (event.type == SDL_QUIT) running = false;
            if (event.type == SDL_KEYDOWN) {
                if (event.key.keysym.sym == SDLK_ESCAPE) running = false;
                if (event.key.keysym.sym == SDLK_F1) showOverlay = !showOverlay;
            }
        }

        lyrics::StateSnapshot snap = state.snapshot();

        ImGui_ImplSDLRenderer2_NewFrame();
        ImGui_ImplSDL2_NewFrame();
        ImGui::NewFrame();

        // Pannello di stato minimale - punto di partenza gia' pronto per impostazioni future
        // (font size, indirizzo del master, ...) senza dover cablare da zero SDL2+ImGui.
        if (showOverlay) {
            ImGui::SetNextWindowPos(ImVec2(12, 12), ImGuiCond_FirstUseEver);
            ImGui::Begin("Status (F1 to hide)", &showOverlay, ImGuiWindowFlags_AlwaysAutoResize);
            ImGui::Text("Connected: %s", snap.connected ? "yes" : "no");
            ImGui::TextWrapped("%s", snap.info.c_str());
            ImGui::Separator();
            ImGui::Text("Status: %s", snap.status.c_str());
            ImGui::End();
        }

        ImGui::Render();

        int width = 0;
        int height = 0;
        SDL_GetRendererOutputSize(renderer, &width, &height);

        SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
        SDL_RenderClear(renderer);

        drawLyrics(renderer, fontCurrent, fontDim, snap, width, height);

        ImGui_ImplSDLRenderer2_RenderDrawData(ImGui::GetDrawData(), renderer);
        SDL_RenderPresent(renderer);
    }

    stopFlag.store(true);
    netThread.join();  // puo' impiegare fino a ~1s (timeout di recv) per accorgersi dello stop

    ImGui_ImplSDLRenderer2_Shutdown();
    ImGui_ImplSDL2_Shutdown();
    ImGui::DestroyContext();

    if (fontCurrent) TTF_CloseFont(fontCurrent);
    if (fontDim) TTF_CloseFont(fontDim);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    TTF_Quit();
    SDL_Quit();
    return 0;
}
