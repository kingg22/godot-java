#!/usr/bin/env bash

set -e

# --- Helpers ---
error() {
  echo "❌ $1"
  exit 1
}

info() {
  echo "👉 $1"
}

# --- Get version (arg or prompt) ---
VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
  read -rp "Enter Godot version (X.X.X): " VERSION
fi

if [[ ! $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  error "Invalid version format. Use X.X.X"
fi

MAJOR=$(echo "$VERSION" | cut -d. -f1)
MINOR=$(echo "$VERSION" | cut -d. -f2)
PATCH=$(echo "$VERSION" | cut -d. -f3)

if [[ "$PATCH" == "0" ]]; then
  VERSION_TAG="${MAJOR}.${MINOR}-stable"
else
  VERSION_TAG="${MAJOR}.${MINOR}.${PATCH}-stable"
fi

TARGET_FOLDER="v${MAJOR}_${MINOR}_${PATCH}"

info "Using version tag: $VERSION_TAG"

# --- Resolve working directory (max depth 3) ---
resolve_workdir() {
  local cwd="$PWD"

  for _ in {1..3}; do
    BASENAME=$(basename "$cwd")

    if [[ "$BASENAME" == "$TARGET_FOLDER" ]]; then
      info "Already inside target folder: $cwd"
      cd "$cwd"
      return
    fi

    if [[ "$BASENAME" == "godot-version" ]]; then
      mkdir -p "$cwd/$TARGET_FOLDER"
      cd "$cwd/$TARGET_FOLDER"
      return
    fi

    cwd=$(dirname "$cwd")
  done

  # fallback: create locally
  info "Creating local godot-version/$TARGET_FOLDER"
  mkdir -p "godot-version/$TARGET_FOLDER"
  cd "godot-version/$TARGET_FOLDER"
}

resolve_workdir

info "Working in $(pwd)"

# --- Locate Godot ---
GODOT_BIN=""
OS_NAME="$(uname -s 2>/dev/null || echo unknown)"

# On macOS, Godot ships as a .app bundle; the actual CLI lives inside it at
# Contents/MacOS/Godot. Given a path, resolve it to that real binary if needed.
resolve_godot_bin() {
  local path="$1"

  if [[ "$OS_NAME" == "Darwin" && "$path" == *.app ]]; then
    local mac_bin="$path/Contents/MacOS/Godot"
    if [[ -x "$mac_bin" ]]; then
      echo "$mac_bin"
      return
    fi

    mac_bin=$(find "$path/Contents/MacOS" -maxdepth 1 -type f -perm -u+x 2>/dev/null | head -n1 || true)
    if [[ -n "$mac_bin" ]]; then
      echo "$mac_bin"
      return
    fi
  fi

  echo "$path"
}

# 1. PATH
if command -v godot &> /dev/null; then
  GODOT_BIN=$(command -v godot)
fi

# 2. Downloads (OS-specific lookup)
if [[ -z "$GODOT_BIN" ]]; then
  DOWNLOADS="$HOME/Downloads"

  if [[ -d "$DOWNLOADS" ]]; then
    CANDIDATE=""

    case "$OS_NAME" in
      Darwin)
        CANDIDATE=$(find "$DOWNLOADS" -maxdepth 2 -type d -iname "*Godot*${VERSION_TAG}*.app" 2>/dev/null | head -n1 || true)
        [[ -n "$CANDIDATE" ]] && CANDIDATE="$(resolve_godot_bin "$CANDIDATE")"
        ;;
      MINGW*|MSYS*|CYGWIN*)
        CANDIDATE=$(find "$DOWNLOADS" -maxdepth 2 -type f -iname "*Godot*${VERSION_TAG}*.exe" 2>/dev/null | head -n1 || true)
        ;;
      *)
        CANDIDATE=$(find "$DOWNLOADS" -maxdepth 2 -type f -iname "*Godot*${VERSION_TAG}*" 2>/dev/null | head -n1 || true)
        ;;
    esac

    if [[ -n "$CANDIDATE" && -x "$CANDIDATE" ]]; then
      GODOT_BIN="$CANDIDATE"
    fi
  fi
fi

# 3. Ask user
while [[ -z "$GODOT_BIN" ]]; do
  if [[ "$OS_NAME" == "Darwin" ]]; then
    read -rp "Path to Godot executable (or the .app bundle): " INPUT_PATH
  else
    read -rp "Path to Godot executable: " INPUT_PATH
  fi

  RESOLVED_PATH="$(resolve_godot_bin "$INPUT_PATH")"
  if [[ -x "$RESOLVED_PATH" ]]; then
    GODOT_BIN="$RESOLVED_PATH"
  else
    echo "Not executable, try again."
  fi
done

info "Using Godot: $GODOT_BIN"

# --- Validate version ---
RAW_VERSION=$("$GODOT_BIN" --version || true)

if [[ -z "$RAW_VERSION" ]]; then
  error "Failed to get version from Godot"
fi

info "Detected version: $RAW_VERSION"

if [[ "$RAW_VERSION" != *"$MAJOR.$MINOR"* ]]; then
  echo "⚠️ Version mismatch. Expected $VERSION"
  read -rp "Continue anyway? (y/N): " CONTINUE
  [[ "$CONTINUE" =~ ^[Yy]$ ]] || exit 1
fi

# --- Dumps ---
info "Dumping extension API (without docs)"
"$GODOT_BIN" --dump-extension-api --headless > /dev/null
mv extension_api.json extension_api_without_docs.json

info "Dumping extension API (with docs)"
"$GODOT_BIN" --dump-extension-api-with-docs --headless > /dev/null

info "Dumping gdextension interface header"
"$GODOT_BIN" --dump-gdextension-interface --headless > /dev/null

info "Dumping gdextension interface JSON"
"$GODOT_BIN" --dump-gdextension-interface-json --headless > /dev/null || true

# --- Download schema ---
SCHEMA_URL="https://raw.githubusercontent.com/godotengine/godot/refs/tags/${VERSION_TAG}/core/extension/gdextension_interface.schema.json"

info "Downloading schema from $SCHEMA_URL"

if command -v curl &> /dev/null; then
  curl -fLo gdextension_interface.schema.json "$SCHEMA_URL"
elif command -v wget &> /dev/null; then
  wget -O gdextension_interface.schema.json "$SCHEMA_URL"
else
  error "Neither curl nor wget installed"
fi

info "✅ Done!"
