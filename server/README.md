# Watch server (aaPanel + Ubuntu)

Malý Node.js server s WebSocketem. Běží na `127.0.0.1:3100`, ven ho vystaví Nginx přes HTTPS.

## 1. Nahrání
1. V aaPanelu: **Files** → `/www/wwwroot/watch-server` (vytvoř složku).
2. Nahraj obsah složky `server/` (`server.js`, `package.json`). Složku `node_modules` nenahrávej.
3. V aaPanelu **Terminal** (nebo SSH):
   ```
   cd /www/wwwroot/watch-server
   npm install --omit=dev
   ```
   Pokud nemáš Node.js, nainstaluj ho v aaPanelu: **App Store → Node.js version manager** (verze 18 nebo novější).

## 2. Spuštění na pozadí
Nejjednodušší je PM2:
```
npm install -g pm2
cd /www/wwwroot/watch-server
pm2 start server.js --name watch
pm2 save
pm2 startup
```
(Příkaz, který `pm2 startup` vypíše, zkopíruj a spusť. Server se pak spustí i po restartu VPS.)

Kontrola: `curl http://127.0.0.1:3100` vrátí `ok`.

## 3. Doména a HTTPS
1. aaPanel → **Website → Add site**: zadej doménu (např. `watch.tvoje-domena.cz`, A záznam musí mířit na VPS). Nepotřebuješ PHP ani databázi.
2. Otevři site → **SSL → Let's Encrypt** → Apply (HTTPS).
3. Site → **Config** (konfigurace Nginx) a do bloku `server { ... }` přidej před ostatní `location`:
   ```
   location ^~ /ws {
       proxy_pass http://127.0.0.1:3100;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-For $remote_addr;
       proxy_read_timeout 3600s;
       proxy_send_timeout 3600s;
   }
   ```
   Ulož a restartuj Nginx.
4. Ověř, že na VPS (firewall v aaPanelu) je otevřený port 443.

## 4. Adresa v aplikaci
V `gradle.properties` v kořeni projektu nastav:
```
serverUrl=wss://watch.tvoje-domena.cz/ws
```
a znovu sestav aplikaci (`gradlew assembleRelease`).

## Poznámky
- Stav (kódy, blokace) se ukládá do `data.json` vedle `server.js`. Zálohovat není nutné; po ztrátě se telefony prostě znovu spárují.
- Kód je 8 znaků a slouží jako heslo. Server omezuje neplatné pokusy (10 za minutu na IP). Neukazuj kód nikomu cizímu.
- Logy: `pm2 logs watch`.
