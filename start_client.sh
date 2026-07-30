#!/bin/bash
cd "$(dirname "$0")"
mkdir -p log
nohup erl -noinput -noshell -pa ./_build/default/lib/*/ebin +K true -config app -s make_proxy start_client >> log/client.log 2>&1 &
