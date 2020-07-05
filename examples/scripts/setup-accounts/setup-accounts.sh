#!/bin/bash

printf "Adding Alice's account...\n"
ilp-cli accounts create alice \
    --auth alice_auth_token \
    --ilp-address example.alice \
    --asset-code "coin#test" \
    --asset-scale 2 \
    --max-packet-amount 100 \
    --ilp-over-http-incoming-token in_alice \
    --settle-to 0 &

printf "Adding Bob's Account...\n"
ilp-cli --node http://localhost:8770 accounts create bob \
    --auth bob_auth_token \
    --ilp-address example.bob \
    --asset-code "coin#test" \
    --asset-scale 2 \
    --max-packet-amount 100 \
    --ilp-over-http-incoming-token in_bob \
    --settle-to 0 &

# This will trigger a SE setup account action on Alice's side
printf "Adding Bob's account on Alice's node...\n"
ilp-cli accounts create bob \
    --auth alice_auth_token \
    --ilp-address example.bob \
    --asset-code "coin#test" \
    --asset-scale 2 \
    --max-packet-amount 100 \
    --settlement-engine-url http://alice-settlement:3000 \
    --ilp-over-http-incoming-token bob_password \
    --ilp-over-http-outgoing-token alice_password \
    --ilp-over-http-url http://bob-node:8770/accounts/alice/ilp \
    --settle-threshold 500 \
    --min-balance -1000 \
    --settle-to 0 \
    --routing-relation Peer &

# This will trigger a SE setup account action on Bob's side
printf "Adding Alice's account on Bob's node...\n"
ilp-cli --node http://localhost:8770 accounts create alice \
    --auth bob_auth_token \
    --ilp-address example.alice \
    --asset-code "coin#test" \
    --asset-scale 2 \
    --max-packet-amount 100 \
    --settlement-engine-url http://bob-settlement:3001 \
    --ilp-over-http-incoming-token alice_password \
    --ilp-over-http-outgoing-token bob_password \
    --ilp-over-http-url http://alice-node:7770/accounts/bob/ilp \
    --settle-threshold 500 \
    --settle-to 0 \
    --routing-relation Peer &