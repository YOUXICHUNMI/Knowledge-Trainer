#!/usr/bin/env python3
"""KT-QBX 入口：python qbext.py build <authoring_dir> [-o x.qbext] / validate <pkg.qbext>

零第三方依赖，Python 3.9+。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ktqbf.qbext import main

if __name__ == "__main__":
    raise SystemExit(main())
