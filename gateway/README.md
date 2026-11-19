# Rayyan Gateway Relay (Digios VPS)

This service replaces the phone-hosted HTTP listener as the EA-facing relay.
It speaks the existing Rayyan4GatewayEA routes and synchronizes state/commands
with APK Mobile Engine through authenticated bridge endpoints.

## Security model
- Listens on 127.0.0.1:8787 only; do not bind it to the public interface.
- Every route requires the X-Rayyan-Token header.
- Keep the token out of source control and do not post it in chat.
- Cloudflare Quick Tunnel exposes the loopback service over HTTPS.

## Install on the VPS
Run from the VPS console as an administrator:

```bash
sudo apt-get update
sudo apt-get install -y python3
sudo useradd --system --home /var/lib/rayyan-gateway --shell /usr/sbin/nologin rayyan-gateway || true
sudo install -d -o rayyan-gateway -g rayyan-gateway /opt/rayyan-gateway /var/lib/rayyan-gateway
```

Download `gateway/server.py` from the RAYYAN branch to
`/opt/rayyan-gateway/server.py`, then create a private token file:

```bash
sudo sh -c 'umask 077; printf "RAYYAN_GATEWAY_TOKEN=%s\\n" "$(openssl rand -hex 32)" > /etc/rayyan-gateway.env'
sudo chmod 600 /etc/rayyan-gateway.env
sudo chown root:root /etc/rayyan-gateway.env
```

Install `gateway/rayyan-gateway.service` at
`/etc/systemd/system/rayyan-gateway.service`, then run:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now rayyan-gateway
sudo systemctl status rayyan-gateway --no-pager
```

Keep the generated token private. Read it locally on the VPS with
`sudo cat /etc/rayyan-gateway.env` and enter the value in APK Gateway settings
and in the MT5 Rayyan4GatewayEA input `GatewayToken`.

## Cloudflare Quick Tunnel
Start this after the local service is active:

```bash
cloudflared tunnel --url http://127.0.0.1:8787
```

Copy the newly generated `https://....trycloudflare.com` URL into:
- APK: Gateway / Queue / ACK → ATUR SERVER VPS
- MT5: Rayyan4GatewayEA → `GatewayUrl`

Quick Tunnel URLs are temporary and can change when the process restarts.
Add the exact HTTPS URL to MT5 Tools → Options → Expert Advisors → Allow WebRequest.

## Validation
Use the exact URL and token to request `/health`. A successful response starts
with `Rayyan gateway OK`. The APK diagnostics should then report remote relay sync
as PASS and the EA polling heartbeat should advance.
