%%%-------------------------------------------------------------------
%%% @author wang
%%% @copyright (C) 2016, <COMPANY>
%%% @doc
%%%
%%% @end
%%% Created : 12. Oct 2016 下午5:26
%%%-------------------------------------------------------------------
-module(make_proxy).
-author("wang").

%% API
-export([start_server/0,
    start_client/0]).

start_server() ->
    {ok, _} = application:ensure_all_started(make_proxy),
    {ok, Port} = application:get_env(make_proxy, server_port),

    PrivDir = code:priv_dir(make_proxy),
    TransOpts = #{
        socket_opts => [
            {port, Port},
            {certfile, filename:join(PrivDir, "server.crt")},
            {keyfile, filename:join(PrivDir, "server.key")}
        ],
        num_acceptors => 20,
        max_connections => infinity
    },

    {ok, _} = ranch:start_listener(
        make_proxy_server,
        ranch_ssl,
        TransOpts,
        mp_server_worker, []
    ).

start_client() ->
    {ok, _} = application:ensure_all_started(make_proxy),
    {ok, Port} = application:get_env(make_proxy, client_port),

    TransOpts = #{
        socket_opts => [{port, Port}],
        num_acceptors => 20,
        max_connections => infinity
    },

    {ok, _} = ranch:start_listener(
        make_proxy_client,
        ranch_tcp,
        TransOpts,
        mp_client_worker, []
    ).
