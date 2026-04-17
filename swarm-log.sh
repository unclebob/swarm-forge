#!/usr/bin/env zsh
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
echo "[$TIMESTAMP] [$1] $2" >> logs/agent_messages.log
echo "[$1] $2"
