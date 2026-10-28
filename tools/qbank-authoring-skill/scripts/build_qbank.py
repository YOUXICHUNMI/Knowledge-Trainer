#!/usr/bin/env python3
"""KT-QBF 打包入口：python build_qbank.py <authoring_dir> [-o out.qbank]

零第三方依赖，Python 3.9+。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ktqbf.builder import main

if __name__ == "__main__":
    raise SystemExit(main())
