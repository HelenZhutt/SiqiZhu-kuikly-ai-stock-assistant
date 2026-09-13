#!/bin/zsh

set -e
cd "$(dirname "$0")"

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "请粘贴 OpenAI API Key（输入不会显示，也不会写入项目文件）："
  read -s OPENAI_API_KEY
  echo
  export OPENAI_API_KEY
fi

exec node openai_proxy.mjs
