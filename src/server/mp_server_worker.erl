-module(mp_server_worker).

-behaviour(gen_server).
-behaviour(ranch_protocol).

%% API
-export([start_link/3]).

%% gen_server callbacks
-export([init/1,
    handle_call/3,
    handle_cast/2,
    handle_info/2,
    terminate/2,
    code_change/3]).

-record(state, {
    key :: binary(),
    username :: binary(),
    password :: binary(),
    peer :: term(),
    ref :: ranch:ref(),
    socket :: any(),
    transport :: module(),
    ok,
    closed,
    error,
    recv_from_client = false :: boolean(),
    recv_from_target = false :: boolean(),
    up_bytes = 0 :: non_neg_integer(),
    down_bytes = 0 :: non_neg_integer(),
    remote :: gen_tcp:socket() | undefined
}).


-define(TIMEOUT, 1000 * 60 * 30).


%%%===================================================================
%%% API
%%%===================================================================

%%--------------------------------------------------------------------
%% @doc
%% Starts the server
%%
%% @spec start_link() -> {ok, Pid} | ignore | {error, Error}
%% @end
%%--------------------------------------------------------------------
start_link(Ref, Transport, Opts) ->
    {ok, proc_lib:spawn_link(?MODULE, init, [{Ref, Transport, Opts}])}.


%%%===================================================================
%%% gen_server callbacks
%%%===================================================================

%%--------------------------------------------------------------------
%% @private
%% @doc
%% Initializes the server
%%
%% @spec init(Args) -> {ok, State} |
%%                     {ok, State, Timeout} |
%%                     ignore |
%%                     {stop, Reason}
%% @end
%%--------------------------------------------------------------------
init({Ref, Transport, _Opts}) ->
    {ok, Socket} = ranch:handshake(Ref),
    Peer = peer(Transport, Socket),
    {ok, Password} = application:get_env(make_proxy, password),
    {ok, Username} = application:get_env(make_proxy, username),
    Key = mp_crypto:derive_key(Password),
    {OK, Closed, Error, _Passive} = Transport:messages(),

    ok = Transport:setopts(Socket, [{active, once}, {packet, 4}]),

    log("new connection from ~p", [Peer]),

    State = #state{key = Key, username = list_to_binary(Username),
        password = list_to_binary(Password), peer = Peer, ref = Ref,
        socket = Socket, transport = Transport, ok = OK, closed = Closed,
        error = Error},

    gen_server:enter_loop(?MODULE, [], State, ?TIMEOUT).

%%--------------------------------------------------------------------
%% @private
%% @doc
%% Handling call messages
%%
%% @spec handle_call(Request, From, State) ->
%%                                   {reply, Reply, State} |
%%                                   {reply, Reply, State, Timeout} |
%%                                   {noreply, State} |
%%                                   {noreply, State, Timeout} |
%%                                   {stop, Reason, Reply, State} |
%%                                   {stop, Reason, State}
%% @end
%%--------------------------------------------------------------------
handle_call(_Request, _From, State) ->
    Reply = ok,
    {reply, Reply, State}.

%%--------------------------------------------------------------------
%% @private
%% @doc
%% Handling cast messages
%%
%% @spec handle_cast(Msg, State) -> {noreply, State} |
%%                                  {noreply, State, Timeout} |
%%                                  {stop, Reason, State}
%% @end
%%--------------------------------------------------------------------
handle_cast(_Msg, State) ->
    {noreply, State}.


%%--------------------------------------------------------------------
%% @private
%% @doc
%% Handling all non call/cast messages
%%
%% @spec handle_info(Info, State) -> {noreply, State} |
%%                                   {noreply, State, Timeout} |
%%                                   {stop, Reason, State}
%% @end
%%--------------------------------------------------------------------

%% first message from client: authentication + target
handle_info({OK, Socket, Request},
    #state{key = Key, socket = Socket, peer = Peer,
        transport = Transport, ok = OK, remote = undefined} = State) ->

    case authenticate(Request, State) of
        {ok, Address, Port} ->
            log("auth ok from ~p, target ~p:~p", [Peer, Address, Port]),
            case connect_target(Address, Port) of
                {ok, Remote} ->
                    ok = reply(Socket, Transport, Key, ok),
                    ok = Transport:setopts(Socket, [{active, once}]),
                    {noreply, State#state{remote = Remote}, ?TIMEOUT};
                {error, Error} ->
                    log("failed to connect target ~p:~p for ~p: ~p",
                        [Address, Port, Peer, Error]),
                    ok = reply(Socket, Transport, Key, {error, connect_failure}),
                    {stop, normal, State}
            end;
        {error, auth_failure} ->
            log("auth failure from ~p", [Peer]),
            ok = reply(Socket, Transport, Key, {error, auth_failure}),
            {stop, normal, State};
        {error, Error} ->
            log("bad first message from ~p: ~p", [Peer, Error]),
            {stop, normal, State}
    end;


%% recv from client, then send to server
handle_info({OK, Socket, Request},
    #state{key = Key, socket = Socket, peer = Peer, recv_from_client = First,
        transport = Transport, ok = OK, remote = Remote} = State) ->

    {ok, RealData} = mp_crypto:decrypt(Key, Request),

    case First of
        false -> log("first data from client ~p (~p bytes)", [Peer, byte_size(RealData)]);
        true -> ok
    end,

    case gen_tcp:send(Remote, RealData) of
        ok ->
            ok = Transport:setopts(Socket, [{active, once}]),
            {noreply, State#state{recv_from_client = true,
                up_bytes = State#state.up_bytes + byte_size(RealData)}, ?TIMEOUT};
        {error, Error} ->
            {stop, Error, State}
    end;


%% recv from server, and send back to client
handle_info({tcp, Remote, Response},
    #state{key = Key, socket = Client, peer = Peer, recv_from_target = First,
        transport = Transport, remote = Remote} = State) ->

    case First of
        false -> log("first data from target for ~p (~p bytes)", [Peer, byte_size(Response)]);
        true -> ok
    end,

    case Transport:send(Client, mp_crypto:encrypt(Key, Response)) of
        ok ->
            ok = inet:setopts(Remote, [{active, once}]),
            {noreply, State#state{recv_from_target = true,
                down_bytes = State#state.down_bytes + byte_size(Response)}, ?TIMEOUT};
        {error, Error} ->
            {stop, Error, State}
    end;

handle_info({Closed, _}, #state{closed = Closed, peer = Peer} = State) ->
    log("client ~p disconnected", [Peer]),
    {stop, normal, State};

handle_info({Error, _, Reason}, #state{error = Error, peer = Peer} = State) ->
    log("client ~p socket error: ~p", [Peer, Reason]),
    {stop, Reason, State};

handle_info({tcp_closed, _}, #state{peer = Peer} = State) ->
    log("target socket closed for ~p", [Peer]),
    {stop, normal, State};

handle_info({tcp_error, _, Reason}, #state{peer = Peer} = State) ->
    log("target socket error for ~p: ~p", [Peer, Reason]),
    {stop, Reason, State};

handle_info(timeout, #state{peer = Peer} = State) ->
    log("connection ~p idle timeout", [Peer]),
    {stop, normal, State}.


%%--------------------------------------------------------------------
%% @private
%% @doc
%% This function is called by a gen_server when it is about to
%% terminate. It should be the opposite of Module:init/1 and do any
%% necessary cleaning up. When it returns, the gen_server terminates
%% with Reason. The return value is ignored.
%%
%% @spec terminate(Reason, State) -> void()
%% @end
%%--------------------------------------------------------------------
terminate(_Reason, #state{socket = Socket, transport = Transport, remote = Remote,
        peer = Peer, up_bytes = Up, down_bytes = Down}) ->
    log("connection closed for ~p, up ~p bytes, down ~p bytes", [Peer, Up, Down]),
    case is_port(Socket) of
        true -> Transport:close(Socket);
        false -> ok
    end,

    case is_port(Remote) of
        true -> gen_tcp:close(Remote);
        false -> ok
    end.

%%--------------------------------------------------------------------
%% @private
%% @doc
%% Convert process state when code is changed
%%
%% @spec code_change(OldVsn, State, Extra) -> {ok, NewState}
%% @end
%%--------------------------------------------------------------------
code_change(_OldVsn, State, _Extra) ->
    {ok, State}.

%%%===================================================================
%%% Internal functions
%%%===================================================================

-spec authenticate(binary(), #state{}) ->
    {ok, inet:ip_address() | nonempty_string(), inet:port_number()} |
    {error, term()}.
authenticate(Data, #state{key = Key, username = Username, password = Password}) ->
    case mp_crypto:decrypt(Key, Data) of
        {ok, RealData} ->
            case binary_to_term(RealData, [safe]) of
                {auth, Username, Password, {Address, Port}} when is_binary(Address) ->
                    {ok, binary_to_list(Address), Port};
                {auth, Username, Password, {Address, Port}} ->
                    {ok, Address, Port};
                {auth, _, _, _} ->
                    {error, auth_failure};
                _ ->
                    {error, invalid_request}
            end;
        {error, Error} ->
            {error, Error}
    end.

-spec reply(any(), module(), binary(), term()) -> ok.
reply(Socket, Transport, Key, Term) ->
    Transport:send(Socket, mp_crypto:encrypt(Key, term_to_binary(Term))).


-spec connect_target(inet:ip_address() | nonempty_string(), inet:port_number()) ->
    {ok, inet:socket()} | {error, term()}.
connect_target(Address, Port) ->
    connect_target(Address, Port, 2).

connect_target(_, _, 0) ->
    {error, connect_failure};

connect_target(Address, Port, RetryTimes) ->
    case gen_tcp:connect(Address, Port, [binary, {active, once}, {keepalive, true}], 5000) of
        {ok, TargetSocket} ->
            log("connected to target ~p:~p", [Address, Port]),
            {ok, TargetSocket};
        {error, _Error} ->
            connect_target(Address, Port, RetryTimes - 1)
    end.


-spec peer(module(), term()) -> term().
peer(Transport, Socket) ->
    case Transport:peername(Socket) of
        {ok, Peer} -> Peer;
        _ -> undefined
    end.


-spec log(string(), list()) -> ok.
log(Fmt, Args) ->
    io:format("~p ~ts~n", [calendar:local_time(), io_lib:format(Fmt, Args)]).
