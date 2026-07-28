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

%% Connect to the proxy server and authenticate with username/password.
%% The first encrypted message is {auth, Username, Password, Target},
%% the server replies with an encrypted `ok' or `{error, Reason}' term.
-spec connect_to_remote(binary(), {inet:ip_address() | nonempty_string(), inet:port_number()}) ->
    {ok, inet:socket()} | {error, term()}.
connect_to_remote(Key, Target) ->
    {ok, RemoteAddr} = application:get_env(make_proxy, server_addr),
    {ok, RemotePort} = application:get_env(make_proxy, server_port),
    {ok, Username} = application:get_env(make_proxy, username),
    {ok, Password} = application:get_env(make_proxy, password),
    {ok, Addr} = inet:getaddr(RemoteAddr, inet),

    case gen_tcp:connect(Addr, RemotePort,
        [binary, {active, false}, {packet, 4}], ?CONNECT_TIMEOUT) of
        {ok, Socket} ->
            Auth = term_to_binary({auth,
                list_to_binary(Username), list_to_binary(Password), Target}),
            ok = gen_tcp:send(Socket, mp_crypto:encrypt(Key, Auth)),
            wait_auth_reply(Key, Socket);
        {error, Reason} ->
            {error, Reason}
    end.

-spec wait_auth_reply(binary(), inet:socket()) ->
    {ok, inet:socket()} | {error, term()}.
wait_auth_reply(Key, Socket) ->
    case gen_tcp:recv(Socket, 0, ?CONNECT_TIMEOUT) of
        {ok, Reply} ->
            case mp_crypto:decrypt(Key, Reply) of
                {ok, Data} ->
                    case binary_to_term(Data, [safe]) of
                        ok ->
                            ok = inet:setopts(Socket, [{active, once}]),
                            {ok, Socket};
                        {error, Reason} ->
                            gen_tcp:close(Socket),
                            {error, Reason}
                    end;
                {error, Reason} ->
                    gen_tcp:close(Socket),
                    {error, Reason}
            end;
        {error, Reason} ->
            gen_tcp:close(Socket),
            {error, Reason}
    end.
