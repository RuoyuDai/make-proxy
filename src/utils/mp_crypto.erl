-module(mp_crypto).
-export([derive_key/1,
         encrypt/2,
         decrypt/2]).

-define(IV_LENGTH, 12).
-define(TAG_LENGTH, 16).
-define(KEY_LENGTH, 16).
-define(PBKDF2_ITERATIONS, 10000).
-define(SALT, <<"make-proxy">>).

%% Derive a 128-bit encryption key from the configured password.
-spec derive_key(string() | binary()) -> binary().
derive_key(Password) when is_list(Password) ->
    derive_key(list_to_binary(Password));
derive_key(Password) when is_binary(Password) ->
    crypto:pbkdf2_hmac(sha256, Password, ?SALT, ?PBKDF2_ITERATIONS, ?KEY_LENGTH).

%% AES-128-GCM: random IV per message, ciphertext is authenticated.
%% Output layout: <<IV:12, Tag:16, CipherText/binary>>
-spec encrypt(binary(), binary()) -> binary().
encrypt(Key, Binary) ->
    IV = crypto:strong_rand_bytes(?IV_LENGTH),
    {CipherText, Tag} = crypto:crypto_one_time_aead(
        aes_128_gcm, Key, IV, Binary, <<>>, ?TAG_LENGTH, true),
    <<IV/binary, Tag/binary, CipherText/binary>>.

-spec decrypt(binary(), binary()) -> {ok, binary()} |
                                     {error, term()}.
decrypt(Key, <<IV:?IV_LENGTH/binary, Tag:?TAG_LENGTH/binary, CipherText/binary>>) ->
    case crypto:crypto_one_time_aead(aes_128_gcm, Key, IV, CipherText, <<>>, Tag, false) of
        error ->
            {error, decrypt_failed};
        PlainText ->
            {ok, PlainText}
    end;

decrypt(_, _) ->
    {error, invalid_data}.
