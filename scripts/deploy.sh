#!/usr/bin/env bash
#
# Telegram Files Docker One-Line Deployment & Management Script
# GitHub: https://github.com/jarvis2f/telegram-files
#

set -e

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Detect Docker Compose command
get_docker_compose() {
    if docker compose version >/dev/null 2>&1; then
        echo "docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        echo "docker-compose"
    else
        echo ""
    fi
}

DOCKER_COMPOSE_CMD=""

# Print banner
print_banner() {
    echo -e "${CYAN}${BOLD}"
    echo "========================================================="
    echo "            Telegram Files Docker Manager                "
    echo "========================================================="
    echo -e "${NC}"
}

# Check OS and Architecture
check_os_arch() {
    local os_name
    os_name="$(uname -s)"
    local arch_name
    arch_name="$(uname -m)"

    if [ "$os_name" != "Linux" ]; then
        echo -e "${RED}Error: Unsupported operating system (${os_name}). Only Linux (amd64 / arm64) is supported.${NC}"
        exit 1
    fi

    case "$arch_name" in
        x86_64|amd64|aarch64|arm64)
            ;;
        *)
            echo -e "${RED}Error: Unsupported system architecture (${arch_name}). Only amd64 (x86_64) and arm64 (aarch64) are supported.${NC}"
            exit 1
            ;;
    esac
}

# Check system prerequisites
check_prerequisites() {
    check_os_arch

    if ! command -v docker >/dev/null 2>&1; then
        echo -e "${RED}Error: Docker is not installed. Please install Docker first.${NC}"
        echo -e "Refer to installation guide: https://docs.docker.com/get-docker/"
        exit 1
    fi

    DOCKER_COMPOSE_CMD=$(get_docker_compose)

    if [ -z "$DOCKER_COMPOSE_CMD" ]; then
        echo -e "${RED}Error: Docker Compose is not installed.${NC}"
        echo -e "Please install the Docker Compose plugin or standalone docker-compose."
        exit 1
    fi

    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error: Python 3 (python3) is not installed.${NC}"
        echo -e "Python 3 is required for qBittorrent password hashing. Please install python3 first."
        exit 1
    fi
}

# Ensure compose and script files exist in working directory
ensure_files() {
    local ref="${1:-main}"

    # Auto-save management script to working directory if not present
    if [ ! -f "deploy.sh" ] && [ ! -f "scripts/deploy.sh" ]; then
        echo -e "${YELLOW}Saving deploy.sh to current directory for future management...${NC}"
        if curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/${ref}/scripts/deploy.sh" -o deploy.sh 2>/dev/null || \
           curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/main/scripts/deploy.sh" -o deploy.sh 2>/dev/null; then
            chmod +x deploy.sh 2>/dev/null || true
            echo -e "${GREEN}deploy.sh saved successfully.${NC}"
        fi
    fi

    if [ ! -f "docker-compose.yaml" ] && [ ! -f "docker-compose.yml" ]; then
        echo -e "${YELLOW}docker-compose.yaml not found in current directory. Downloading from GitHub (ref: ${ref})...${NC}"
        if ! curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/${ref}/docker-compose.yaml" -o docker-compose.yaml; then
            echo -e "${YELLOW}Failed to download docker-compose.yaml from ref '${ref}', falling back to 'main'...${NC}"
            curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/main/docker-compose.yaml" -o docker-compose.yaml || {
                echo -e "${RED}Failed to download docker-compose.yaml.${NC}"
                exit 1
            }
        fi
        echo -e "${GREEN}docker-compose.yaml downloaded successfully.${NC}"
    fi

    if [ ! -f ".env.example" ]; then
        curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/${ref}/.env.example" -o .env.example 2>/dev/null || \
        curl -fsSL "https://raw.githubusercontent.com/jarvis2f/telegram-files/main/.env.example" -o .env.example 2>/dev/null || true
    fi
}

# Helper function to generate PBKDF2 hash for qBittorrent password
generate_qbit_pbkdf2() {
    local pass="$1"
    QBIT_PASSWORD="$pass" python3 - <<'PY'
import base64
import hashlib
import os

password = os.environ["QBIT_PASSWORD"].encode()
salt = os.urandom(16)
digest = hashlib.pbkdf2_hmac("sha512", password, salt, 100_000)
print(f"@ByteArray({base64.b64encode(salt).decode()}:{base64.b64encode(digest).decode()})")
PY
}

# Helper function to generate a 32-byte Base64 key for SECRET_STORE_MASTER_KEY
generate_master_key() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32 | tr -d '\r\n'
    elif [ -c /dev/urandom ]; then
        head -c 32 /dev/urandom | base64 | tr -d '\r\n'
    else
        echo "fallback_key_32_bytes_base64_encoded="
    fi
}

# Helper function to generate a random password
generate_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 12
    elif [ -c /dev/urandom ]; then
        tr -dc 'a-zA-Z0-9' < /dev/urandom 2>/dev/null | head -c 16 || echo "pass_$(date +%s)"
    else
        echo "pass_$(date +%s)_$RANDOM"
    fi
}

# Helper function to read from tty safely without interrupting pipe execution
read_tty() {
    local prompt_msg="$1"
    local val=""
    if [ -e /dev/tty ] && [ -r /dev/tty ]; then
        read -r -p "$prompt_msg" val < /dev/tty || true
    else
        read -r -p "$prompt_msg" val || true
    fi
    echo "$val"
}

# Helper function to prompt for input with a default value
prompt_value() {
    local prompt_text="$1"
    local default_val="$2"
    local input_val

    if [ -n "$default_val" ]; then
        input_val=$(read_tty "$(echo -e "${BOLD}${prompt_text}${NC} [Default: ${CYAN}${default_val}${NC}]: ")")
        echo "${input_val:-$default_val}"
    else
        while true; do
            input_val=$(read_tty "$(echo -e "${BOLD}${prompt_text}${NC} (${RED}Required${NC}): ")")
            if [ -n "$input_val" ]; then
                echo "$input_val"
                break
            else
                echo -e "${RED}Input cannot be empty. Please try again.${NC}"
            fi
        done
    fi
}

# Interactive configuration wizard
configure_env() {
    print_banner
    echo -e "${YELLOW}=== Configuring Telegram Files Environment Variables ===${NC}\n"

    # Load existing .env if present
    if [ -f .env ]; then
        echo -e "${BLUE}Existing .env file detected. Press Enter to keep existing values.${NC}\n"
        set -a
        # shellcheck disable=SC1091
        source .env 2>/dev/null || true
        set +a
    fi

    # 1. Version / Tag Selection
    echo -e "${CYAN}1. Version / Image Tag Selection${NC}"
    echo "1) Stable Latest (ghcr.io/jarvis2f/telegram-files:latest) [Default]"
    echo "2) Development (ghcr.io/jarvis2f/telegram-files:dev - dev branch)"
    echo "3) Custom Tag / Release Version (e.g., 0.4.0)"
    ver_choice=$(read_tty "Select version option [1-3, Default: 1]: ")

    case "$ver_choice" in
        2)
            IMAGE_TAG="dev"
            GIT_REF="dev"
            ;;
        3)
            IMAGE_TAG=$(prompt_value "Enter version tag" "${IMAGE_TAG:-0.4.0}")
            GIT_REF="$IMAGE_TAG"
            ;;
        *)
            IMAGE_TAG="latest"
            GIT_REF="main"
            ;;
    esac
    echo -e "Selected Image Tag: ${BOLD}${CYAN}${IMAGE_TAG}${NC}\n"

    # 2. Telegram API Credentials
    echo -e "${CYAN}2. Telegram API Credentials${NC}"
    echo -e "If you don't have API Credentials, apply at ${BLUE}https://my.telegram.org/apps${NC}."
    TELEGRAM_API_ID=$(prompt_value "Enter TELEGRAM_API_ID" "$TELEGRAM_API_ID")
    TELEGRAM_API_HASH=$(prompt_value "Enter TELEGRAM_API_HASH" "$TELEGRAM_API_HASH")
    echo ""

    # 3. Server & Network Settings
    echo -e "${CYAN}3. Server Port & Data Directory${NC}"
    PORT=$(prompt_value "Web service listen port" "${PORT:-6543}")
    DATA_DIR=$(prompt_value "Data mount directory" "${DATA_DIR:-./data}")
    
    input_sec=$(read_tty "$(echo -e "${BOLD}Enable HTTPS Secure Cookies? (true/false)${NC} [Default: ${CYAN}${HTTP_SECURE_COOKIES:-false}${NC}]: ")")
    HTTP_SECURE_COOKIES="${input_sec:-${HTTP_SECURE_COOKIES:-false}}"

    echo -e "\n${BOLD}Select Log Level:${NC}"
    echo "1) INFO (Default)"
    echo "2) DEBUG"
    echo "3) WARNING"
    echo "4) SEVERE"
    log_choice=$(read_tty "Select Log Level [1-4, Default: 1]: ")
    case "$log_choice" in
        2) LOG_LEVEL="FINE" ;;
        3) LOG_LEVEL="WARNING" ;;
        4) LOG_LEVEL="SEVERE" ;;
        *) LOG_LEVEL="INFO" ;;
    esac
    echo ""

    # 4. Database Configuration
    echo -e "${CYAN}4. Database Configuration${NC}"
    echo "1) SQLite (Built-in, zero setup required) [Default]"
    echo "2) PostgreSQL (Container launched via Docker Compose)"
    echo "3) PostgreSQL (Connect to external existing database)"
    echo "4) MySQL / MariaDB (Container launched via Docker Compose)"
    echo "5) MySQL / MariaDB (Connect to external existing database)"
    db_choice=$(read_tty "Select database option [1-5, Default: 1]: ")

    ACTIVE_PROFILES=()

    case "$db_choice" in
        2)
            DB_TYPE="postgres"
            DB_HOST="telegram-files-postgres"
            DB_PORT="5432"
            DB_USER="postgres"
            AUTO_PG_PASS="${DB_PASSWORD:-$(generate_password)}"
            DB_PASSWORD=$(prompt_value "Set PostgreSQL password" "$AUTO_PG_PASS")
            DB_NAME="telegram-files"
            ACTIVE_PROFILES+=("postgres")
            ;;
        3)
            DB_TYPE="postgres"
            DB_HOST=$(prompt_value "PostgreSQL Host / IP" "${DB_HOST:-localhost}")
            DB_PORT=$(prompt_value "PostgreSQL Port" "${DB_PORT:-5432}")
            DB_USER=$(prompt_value "PostgreSQL Username" "${DB_USER:-postgres}")
            DB_PASSWORD=$(prompt_value "PostgreSQL Password" "$DB_PASSWORD")
            DB_NAME=$(prompt_value "PostgreSQL Database Name" "${DB_NAME:-telegram-files}")
            ;;
        4)
            DB_TYPE="mysql"
            DB_HOST="telegram-files-mysql"
            DB_PORT="3306"
            DB_USER="mysql"
            AUTO_MYSQL_PASS="${DB_PASSWORD:-$(generate_password)}"
            DB_PASSWORD=$(prompt_value "Set MySQL password" "$AUTO_MYSQL_PASS")
            DB_NAME="telegram-files"
            ACTIVE_PROFILES+=("mysql")
            ;;
        5)
            DB_TYPE="mysql"
            DB_HOST=$(prompt_value "MySQL Host / IP" "${DB_HOST:-localhost}")
            DB_PORT=$(prompt_value "MySQL Port" "${DB_PORT:-3306}")
            DB_USER=$(prompt_value "MySQL Username" "${DB_USER:-mysql}")
            DB_PASSWORD=$(prompt_value "MySQL Password" "$DB_PASSWORD")
            DB_NAME=$(prompt_value "MySQL Database Name" "${DB_NAME:-telegram-files}")
            ;;
        *)
            DB_TYPE=""
            DB_HOST=""
            DB_PORT=""
            DB_USER=""
            DB_PASSWORD=""
            DB_NAME=""
            ;;
    esac
    echo ""

    # 5. P2P Share / Torrent Module
    echo -e "${CYAN}5. P2P / Torrent Sharing Module (Optional)${NC}"
    share_choice=$(read_tty "$(echo -e "${BOLD}Enable P2P sharing & torrent module? (y/N)${NC} [Default: ${CYAN}N${NC}]: ")")
    case "$share_choice" in
        [yY][eE][sS]|[yY])
            SHARE_ENABLED="true"
            SECRET_STORE_MASTER_KEY="${SECRET_STORE_MASTER_KEY:-$(generate_master_key)}"
            SEED_PLATFORM_URL=$(prompt_value "SEED Platform URL" "${SEED_PLATFORM_URL:-https://tele-seed.com}")
            PEER_LISTEN_PORT=$(prompt_value "P2P Listen Port" "${PEER_LISTEN_PORT:-51413}")
            AUTO_QBIT_PASS="${QBITTORRENT_PASSWORD:-$(generate_password)}"
            QBITTORRENT_PASSWORD=$(prompt_value "qBittorrent WebUI password" "$AUTO_QBIT_PASS")
            ACTIVE_PROFILES+=("share")
            ;;
        *)
            SHARE_ENABLED="false"
            SECRET_STORE_MASTER_KEY=""
            SEED_PLATFORM_URL=""
            PEER_LISTEN_PORT="51413"
            QBITTORRENT_PASSWORD=""
            ;;
    esac

    # Join profiles into comma separated string
    COMPOSE_PROFILES_VAL=""
    if [ ${#ACTIVE_PROFILES[@]} -gt 0 ]; then
        COMPOSE_PROFILES_VAL=$(IFS=,; echo "${ACTIVE_PROFILES[*]}")
    fi

    # Write .env file
    cat <<EOF > .env
# Telegram Files Configuration (Generated by deploy.sh)
IMAGE_TAG=${IMAGE_TAG}
GIT_REF=${GIT_REF}

TELEGRAM_API_ID=${TELEGRAM_API_ID}
TELEGRAM_API_HASH=${TELEGRAM_API_HASH}

PORT=${PORT}
DATA_DIR=${DATA_DIR}
HTTP_SECURE_COOKIES=${HTTP_SECURE_COOKIES}
LOG_LEVEL=${LOG_LEVEL}
TELEGRAM_LOG_LEVEL=0

# Database Settings
DB_TYPE=${DB_TYPE}
DB_HOST=${DB_HOST}
DB_PORT=${DB_PORT}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
DB_NAME=${DB_NAME}

# Shared-node / Torrent Module
SHARE_ENABLED=${SHARE_ENABLED}
SECRET_STORE_MASTER_KEY=${SECRET_STORE_MASTER_KEY}
SEED_PLATFORM_URL=${SEED_PLATFORM_URL}
PEER_LISTEN_PORT=${PEER_LISTEN_PORT}
QBITTORRENT_PASSWORD=${QBITTORRENT_PASSWORD}

# Docker Compose Profiles
COMPOSE_PROFILES=${COMPOSE_PROFILES_VAL}
EOF

    echo -e "\n${GREEN}✓ Configuration saved successfully to .env!${NC}\n"
}

# Pre-initialize qBittorrent configuration for seamless authentication in Docker
setup_qbittorrent_config() {
    if [ "$SHARE_ENABLED" = "true" ]; then
        local data_dir="${DATA_DIR:-./data}"
        local qbit_dir="${data_dir}/qbittorrent/config/qBittorrent"
        local qbit_conf="${qbit_dir}/qBittorrent.conf"
        local peer_port="${PEER_LISTEN_PORT:-51413}"
        local qbit_user="${QBITTORRENT_USERNAME:-admin}"
        local qbit_pass="${QBITTORRENT_PASSWORD:-}"

        if [ -z "$qbit_pass" ]; then
            qbit_pass="$(generate_password)"
            QBITTORRENT_PASSWORD="$qbit_pass"
            # Update QBITTORRENT_PASSWORD in .env if .env exists
            if [ -f .env ]; then
                if grep -q '^QBITTORRENT_PASSWORD=' .env; then
                    sed -i.bak "s|^QBITTORRENT_PASSWORD=.*|QBITTORRENT_PASSWORD=${qbit_pass}|" .env 2>/dev/null || true
                    rm -f .env.bak 2>/dev/null || true
                else
                    echo "QBITTORRENT_PASSWORD=${qbit_pass}" >> .env
                fi
            fi
        fi

        mkdir -p "$qbit_dir" "${data_dir}/shared"

        local qbit_hash
        qbit_hash="$(generate_qbit_pbkdf2 "$qbit_pass")"

        local tmp_conf
        tmp_conf="$(mktemp 2>/dev/null || echo "${qbit_conf}.tmp")"

        if [ -f "$qbit_conf" ]; then
            sed '/^WebUI\\Username=/d;/^WebUI\\Password_PBKDF2=/d;/^WebUI\\HostHeaderValidation=/d;/^WebUI\\ServerDomains=/d;/^WebUI\\Port=/d;/^WebUI\\UseUPnP=/d;/^Session\\Port=/d;/^Session\\DefaultSavePath=/d;/^WebUI\\AuthSubnetWhitelist=/d;/^WebUI\\AuthSubnetWhitelistEnabled=/d;/^WebUI\\LocalHostAuth=/d' "$qbit_conf" > "$tmp_conf"
        else
            : > "$tmp_conf"
        fi

        grep -q '^\[LegalNotice\]' "$tmp_conf" || printf '\n[LegalNotice]\nAccepted=true\n' >> "$tmp_conf"
        grep -q '^\[Preferences\]' "$tmp_conf" || printf '\n[Preferences]\n' >> "$tmp_conf"

        cat >> "$tmp_conf" <<EOF
Session\\DefaultSavePath=/downloads
Session\\Port=$peer_port
WebUI\\Username=$qbit_user
WebUI\\Password_PBKDF2="$qbit_hash"
WebUI\\HostHeaderValidation=true
WebUI\\ServerDomains="telegram-files-qbittorrent;localhost;127.0.0.1"
WebUI\\Port=8080
WebUI\\UseUPnP=false
EOF

        mv "$tmp_conf" "$qbit_conf"
        chmod 600 "$qbit_conf" 2>/dev/null || true

        echo -e "${GREEN}✓ qBittorrent WebUI configuration initialized successfully.${NC}"
    fi
}

# Action: Start / Deploy
do_start() {
    check_prerequisites
    if [ -f .env ]; then
        set -a
        # shellcheck disable=SC1091
        source .env 2>/dev/null || true
        set +a
    else
        echo -e "${YELLOW}No .env file found. Launching initial setup wizard...${NC}\n"
        configure_env
    fi

    ensure_files "${GIT_REF:-main}"
    setup_qbittorrent_config

    echo -e "${CYAN}Starting Telegram Files service (Image Tag: ${IMAGE_TAG:-latest})...${NC}"
    $DOCKER_COMPOSE_CMD up -d

    echo -e "\n${GREEN}=========================================================${NC}"
    echo -e "${GREEN}✓ Telegram Files has been successfully deployed and started!${NC}"
    echo -e "Access URL: ${BOLD}${CYAN}http://localhost:${PORT:-6543}${NC}"
    echo -e "Image Tag:  ${BOLD}${CYAN}${IMAGE_TAG:-latest}${NC}"
    echo -e "On first login, check logs for the 15-minute one-time administrator bootstrap code."
    if [ -f "./scripts/deploy.sh" ]; then
        echo -e "To manage service, run: ${BOLD}./scripts/deploy.sh [start|stop|update|logs|config]${NC}"
    else
        echo -e "To manage service, run: ${BOLD}./deploy.sh [start|stop|update|logs|config]${NC}"
    fi
    echo -e "${GREEN}=========================================================${NC}\n"
}

# Action: Stop
do_stop() {
    check_prerequisites
    echo -e "${YELLOW}Stopping Telegram Files service...${NC}"
    $DOCKER_COMPOSE_CMD down
    echo -e "${GREEN}✓ Service stopped successfully.${NC}"
}

# Action: Update
do_update() {
    check_prerequisites
    if [ -f .env ]; then
        set -a
        # shellcheck disable=SC1091
        source .env 2>/dev/null || true
        set +a
    fi
    ensure_files "${GIT_REF:-main}"
    setup_qbittorrent_config
    echo -e "${CYAN}Pulling latest Docker images (Tag: ${IMAGE_TAG:-latest})...${NC}"
    $DOCKER_COMPOSE_CMD pull
    echo -e "${CYAN}Restarting containers...${NC}"
    $DOCKER_COMPOSE_CMD up -d
    echo -e "${GREEN}✓ Telegram Files updated to latest version (${IMAGE_TAG:-latest}) and restarted successfully!${NC}"
}

# Action: Restart
do_restart() {
    check_prerequisites
    echo -e "${YELLOW}Restarting Telegram Files service...${NC}"
    $DOCKER_COMPOSE_CMD restart
    echo -e "${GREEN}✓ Service restarted successfully.${NC}"
}

# Action: Status
do_status() {
    check_prerequisites
    echo -e "${CYAN}=== Container Status ===${NC}"
    $DOCKER_COMPOSE_CMD ps
}

# Action: Logs
do_logs() {
    check_prerequisites
    echo -e "${CYAN}=== Streaming Live Logs (Press Ctrl+C to exit) ===${NC}"
    $DOCKER_COMPOSE_CMD logs -f --tail=100
}

# Main Interactive Menu
show_menu() {
    print_banner
    echo " 1) Deploy / Start Service"
    echo " 2) Stop Service"
    echo " 3) Update Service"
    echo " 4) Restart Service"
    echo " 5) Check Container Status"
    echo " 6) View Live Logs"
    echo " 7) Reconfigure Environment Variables & Version Tag"
    echo " 0) Exit"
    echo ""
    choice=$(read_tty "Enter choice [0-7]: ")
    echo ""

    case "$choice" in
        1) do_start ;;
        2) do_stop ;;
        3) do_update ;;
        4) do_restart ;;
        5) do_status ;;
        6) do_logs ;;
        7) configure_env && do_start ;;
        0) exit 0 ;;
        *)
            echo -e "${RED}Invalid option. Please try again.${NC}"
            show_menu
            ;;
    esac
}

# Main entry point to guarantee entire script is parsed before execution
main() {
    # Move up to parent directory if script is executed from inside scripts/ directory
    if [ ! -f "docker-compose.yaml" ] && [ -f "../docker-compose.yaml" ]; then
        cd ..
    fi

    case "$1" in
        install|start)
            do_start
            ;;
        stop)
            do_stop
            ;;
        update|upgrade)
            do_update
            ;;
        restart)
            do_restart
            ;;
        status)
            do_status
            ;;
        logs)
            do_logs
            ;;
        config|configure)
            configure_env
            ;;
        help|-h|--help)
            print_banner
            echo "Usage: ./scripts/deploy.sh [command]"
            echo ""
            echo "Available commands:"
            echo "  start / install  - Configure and start Telegram Files service"
            echo "  stop             - Stop running service"
            echo "  update / upgrade - Pull latest images and restart service"
            echo "  restart          - Restart service"
            echo "  status           - View container status"
            echo "  logs             - View live container logs"
            echo "  config           - Reconfigure environment parameters and version tag"
            echo "  (no arguments)   - Show interactive menu"
            ;;
        *)
            show_menu
            ;;
    esac
}

# Invoke main function with all script arguments
main "$@"
