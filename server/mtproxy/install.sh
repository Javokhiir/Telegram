#!/usr/bin/env bash
# U Message MTProto proxy (mtg v2, Fake-TLS) — Ubuntu 22.04/24.04 / Debian 12.
# Usage (as root on the VPS):  bash install.sh [fake-tls-domain]
# Prints the IP / port / secret to paste into UMessageProxyConfig.java.
set -euo pipefail

DOMAIN="${1:-www.microsoft.com}"   # any big HTTPS site reachable from the target country
PORT=443
DIR=/opt/umessage-mtproxy

if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

mkdir -p "$DIR"
if [ ! -f "$DIR/config.toml" ]; then
    SECRET=$(docker run --rm nineseconds/mtg:2 generate-secret --hex "$DOMAIN")
    cat > "$DIR/config.toml" <<EOF
secret = "$SECRET"
bind-to = "0.0.0.0:3128"
concurrency = 8192
prefer-ip = "prefer-ipv4"

[defense.anti-replay]
enabled = true
max-size = "1mib"
EOF
    chmod 600 "$DIR/config.toml"
fi
SECRET=$(grep '^secret' "$DIR/config.toml" | cut -d'"' -f2)

# No container logs at all: client IPs never hit disk.
docker rm -f umessage-mtproxy >/dev/null 2>&1 || true
docker run -d --name umessage-mtproxy --restart always \
    --log-driver none \
    -p "$PORT:3128" -v "$DIR/config.toml:/config.toml:ro" \
    nineseconds/mtg:2 run /config.toml

# Basic hardening: firewall + BBR congestion control (better on long China routes).
if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp >/dev/null
    ufw allow "$PORT/tcp" >/dev/null
    ufw --force enable >/dev/null
fi
grep -q bbr /etc/sysctl.conf || {
    echo "net.core.default_qdisc=fq" >> /etc/sysctl.conf
    echo "net.ipv4.tcp_congestion_control=bbr" >> /etc/sysctl.conf
    sysctl -p >/dev/null
}

IP=$(curl -4 -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo
echo "PROXY_1_IP     = \"$IP\""
echo "PROXY_1_PORT   = $PORT"
echo "PROXY_1_SECRET = \"$SECRET\""
echo "Test link: tg://proxy?server=$IP&port=$PORT&secret=$SECRET"
