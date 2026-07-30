# Make Proxy

## Features

with the great erlang, the project has the following features:

*   support http, https, socks4, socks5,http proxy.
*   Robustness. never down.
*   Scalable. handle thousands requests at the same time easily.
*   Fast.
*   Lightweight.


## Illustrate

```
+------------+            +--------------+          
| local app  |  <=======> | proxy client | <#######
+------------+            +--------------+        #
                                                  #
                                                  #
                                                  # encrypted data
                                                  #
                                                  #
+-------------+            +--------------+       #
| target host |  <=======> | proxy server |  <#####
+-------------+            +--------------+         
```


## Usage

1.  git clone https://github.com/yueyoum/make-proxy.git
2.  cd make-proxy
3.  rebar3 tree && rebar3 compile
4.  cp app.config.example app.config
    
    #### app.config

    *   server_addr - which address that the server listen on
    *   server_port - which port that the server listen on
    *   client_port - which port that the client listen on
    *   username - username that the client uses to authenticate to the server
    *   password - password for authentication, also used to derive the encryption key (PBKDF2-HMAC-SHA256). **Use a long random password** - its strength directly determines the encryption strength.

5.  run `./gen_cert.sh` to generate a self-signed TLS certificate
    (`priv/server.crt` / `priv/server.key`).
6.  run `./start_server.sh` at server side, and `./start_client.sh` at client side.
7.  Done.

## Security

The client <-> server tunnel is protected by two layers:

*   **TLS** - the whole connection runs inside a TLS session
    (self-signed certificate, `verify_none` on the client). This makes
    the traffic look like ordinary HTTPS.
*   **AES-128-GCM** - every message inside the TLS tunnel is encrypted
    with AES-128-GCM (random IV per message, authenticated ciphertext).
    The key is derived from `password` with PBKDF2-HMAC-SHA256
    (10000 iterations). The first message authenticates the client with
    `username` / `password`; the server rejects unknown clients.

## TODO

1.  Support Socks5 Username/Password Authorize
2.  Traffic Statistics
