CXX := g++
CXXFLAGS := -std=c++17 -Wall -Wextra -O2 -Ithird_party/imgui -Ithird_party/imgui/backends
# libcrypto (OpenSSL): only for HMAC-SHA256 in the optional authentication handshake of
# protocol 1.1.0 (MelostixProtocol) - see net_client.cpp. The rest of the client (JSON parsing,
# base64) stays hand-written, no new dependency for that.
LDFLAGS := $(shell pkg-config --libs sdl2 SDL2_ttf 2>/dev/null || echo -lSDL2 -lSDL2_ttf) -lpthread $(shell pkg-config --libs libcrypto 2>/dev/null || echo -lcrypto)

IMGUI_SRC := \
	third_party/imgui/imgui.cpp \
	third_party/imgui/imgui_draw.cpp \
	third_party/imgui/imgui_tables.cpp \
	third_party/imgui/imgui_widgets.cpp \
	third_party/imgui/backends/imgui_impl_sdl2.cpp \
	third_party/imgui/backends/imgui_impl_sdlrenderer2.cpp

SRC := src/main.cpp src/net_client.cpp $(IMGUI_SRC)
OBJ := $(SRC:.cpp=.o)
BIN := melostix-client

.PHONY: all clean

all: $(BIN)

$(BIN): $(OBJ)
	$(CXX) $(OBJ) -o $@ $(LDFLAGS)

%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -f $(OBJ) $(BIN)
