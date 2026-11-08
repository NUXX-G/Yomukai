# Yomukai detrás de Apache

Las direcciones de capítulos, episodios y obras llevan barras codificadas (`%2F`). Apache, por defecto, contesta
**404 «Not Found»** a cualquier dirección que las tenga; por eso recargar (F5) dentro de un capítulo fallaba. Hacen
falta dos cosas en el `VirtualHost` del dominio (el del puerto 443):

```apache
<VirtualHost *:443>
    ServerName yomukai.example.com
    # Deja pasar las barras codificadas sin tocarlas.
    AllowEncodedSlashes NoDecode

    ProxyPreserveHost On
    RequestHeader set X-Forwarded-Proto "https"
    # nocanon: la dirección llega a Yomukai tal cual (sin volver a codificarla).
    ProxyPass        / http://127.0.0.1:8080/ nocanon upgrade=websocket
    ProxyPassReverse / http://127.0.0.1:8080/
    # ... certificados SSL ...
</VirtualHost>
```

Para aplicarlo en una instalación que ya funciona (cambia solo esas dos cosas en los archivos del dominio):

```bash
for F in $(grep -l "yomukai" /etc/apache2/sites-available/*.conf); do
  grep -q "AllowEncodedSlashes" "$F" || sudo sed -i '/<VirtualHost \*:443>/a\    AllowEncodedSlashes NoDecode' "$F"
  grep -q "nocanon" "$F" || sudo sed -i -E 's#^(\s*ProxyPass\s+/\s+http://127\.0\.0\.1:[0-9]+/)#\1 nocanon#' "$F"
done
sudo apache2ctl configtest && sudo systemctl reload apache2
```

Con Nginx o Caddy no hace falta nada: pasan las barras codificadas sin cambios.
