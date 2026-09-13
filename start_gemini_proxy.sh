#!/bin/zsh
set -e

cd "$(dirname "$0")"

proxy_port="${OPENAI_PROXY_PORT:-8787}"
existing_pid="$(lsof -nP -tiTCP:"$proxy_port" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
if [[ -n "$existing_pid" ]]; then
  existing_cwd="$(lsof -a -p "$existing_pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  existing_command="$(ps -p "$existing_pid" -o command= 2>/dev/null || true)"
  if [[ "$existing_cwd" == "$PWD" && "$existing_command" == *"openai_proxy.mjs"* ]]; then
    echo "检测到本项目的旧代理（PID $existing_pid），正在重新启动…"
    kill "$existing_pid"
    for attempt in {1..20}; do
      if ! kill -0 "$existing_pid" 2>/dev/null; then
        break
      fi
      sleep 0.1
    done
    if kill -0 "$existing_pid" 2>/dev/null; then
      echo "旧代理未能正常退出，请先在原终端按 Ctrl+C 后重试。"
      exit 1
    fi
  else
    echo "端口 $proxy_port 已被其他程序占用：$existing_command"
    echo "请先关闭该程序，或设置 OPENAI_PROXY_PORT 使用其他端口。"
    exit 1
  fi
fi

if [[ -z "${GEMINI_API_KEY:-}" ]]; then
  echo "请粘贴 Gemini API Key（输入不会显示，也不会写入项目文件）："
  read -s GEMINI_API_KEY
  echo
  export GEMINI_API_KEY
fi

export AI_PROVIDER=gemini
exec node openai_proxy.mjs
