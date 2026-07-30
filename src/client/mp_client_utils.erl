%%%-------------------------------------------------------------------
%%% @author wang
%%% @copyright (C) 2016, <COMPANY>
%%% @doc
%%%
%%% @end
%%% Created : 13. Oct 2016 下午2:28
%%%-------------------------------------------------------------------
-module(mp_client_utils).
-author("wang").

%% API
-export([connect_to_remote/2]).

-define(CONNECT_TIMEOUT, 5000).

%% Connect to the proxy server over TLS and authenticate with
%% username/password. The first encrypted message is
%% {auth, Username, Password, Target}, the server replies with an
%% encrypted `ok' or `{error, Reason}' term.
%%
%% The TLS certificate is not verified (verify_none): the server uses a
%% self-signed certificate, and TLS here is for traffic camouflage.
%% Confidentiality and peer authentication are guaranteed by the
%% application-layer AES-GCM encryption and username/password auth.
-spec connect_to_remote(binary(), {inet:ip_address() | nonempty_string(), inet:port_number()}) ->
    {ok, ssl:sslsocket()} | {error, term()}.
connect_to_remote(Key, Target) ->
    {ok, RemoteAddr} = application:get_env(make_proxy, server_addr),
    {ok, RemotePort} = application:get_env(make_proxy, server_port),
    {ok, Username} = application:get_env(make_proxy, username),
    {ok, Password} = application:get_env(make_proxy, password),
    {ok, Addr} = inet:getaddr(RemoteAddr, inet),

    SslOpts = [binary, {active, false}, {packet, 4}, {verify, verify_none}],
    case ssl:connect(Addr, RemotePort, SslOpts, ?CONNECT_TIMEOUT) of
        {ok, Socket} ->
            Auth = term_to_binary({auth,
                list_to_binary(Username), list_to_binary(Password), Target}),
            ok = ssl:send(Socket, mp_crypto:encrypt(Key, Auth)),
            wait_auth_reply(Key, Socket);
        {error, Reason} ->
            {error, Reason}
    end.

-spec wait_auth_reply(binary(), ssl:sslsocket()) ->
    {ok, ssl:sslsocket()} | {error, term()}.
wait_auth_reply(Key, Socket) ->
    case ssl:recv(Socket, 0, ?CONNECT_TIMEOUT) of
        {ok, Reply} ->
            case mp_crypto:decrypt(Key, Reply) of
                {ok, Data} ->
                    case binary_to_term(Data, [safe]) of
                        ok ->
                            ok = ssl:setopts(Socket, [{active, once}]),
                            {ok, Socket};
                        {error, Reason} ->
                            ssl:close(Socket),
                            {error, Reason}
                    end;
                {error, Reason} ->
                    ssl:close(Socket),
                    {error, Reason}
            end;
        {error, Reason} ->
            ssl:close(Socket),
            {error, Reason}
    end.
