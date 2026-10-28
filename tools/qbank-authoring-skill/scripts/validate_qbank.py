#!/usr/bin/env python3
"""KT-QBF 校验入口：python validate_qbank.py validate <pkg.qbank> [--json]

子命令：validate / inspect / unpack / digest / answer-hash。零第三方依赖，Python 3.9+。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ktqbf.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
