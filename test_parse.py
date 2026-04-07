# -*- coding: utf-8 -*-
"""测试 SGF 解析"""
import re
import sys
sys.path.insert(0, r'D:\go-analyzer\server')

from server import parse_sgf

# 测试各种 SGF 格式
test_cases = [
    # 普通格式
    '(;GM[1]SZ[19]KM[7.5];B[aa];W[bb];B[cc];W[dd])',
    # 无空格连续格式
    '(;GM[1]SZ[19];B[aa]W[bb]B[cc]W[dd])',
    # 含 setup 的让子棋
    '(;GM[1]SZ[19]KM[7.5]AB[aa][bb][cc]AW[dd][ee][ff];B[gg];W[hh])',
    # 含 comment
    '(;GM[1]SZ[19];B[aa]C[comment]W[bb])',
    # 大写坐标
    '(;GM[1]SZ[19];B[AA];W[BB])',
    # pass
    '(;GM[1]SZ[19];B[aa];W[tt];B[cc])',
    # 多行格式
    '(;GM[1]SZ[19]\n;B[aa]\n;W[bb]\n;B[cc])',
    # 含转义
    '(;GM[1]SZ[19];B[aa]W[bb];B[cc]W[dd]B[ee])',
    # 嵌套 variation（SGF 标准格式）
    '(;GM[1]SZ[19];B[aa];W[bb](;B[cc];W[dd])(;B[ee];W[ff]);B[gg])',
]

print("=" * 60)
for tc in test_cases:
    moves, sz, komi, bn, wn = parse_sgf(tc)
    normal = [m for m in moves if not m.get('setup')]
    print(f"Input:  {tc[:60]}")
    print(f"Output: {len(moves)} total, {len(normal)} normal moves")
    for m in moves:
        if m.get('setup'):
            print(f"  [setup] {m['color']} at ({m['x']},{m['y']})")
        elif m.get('pass'):
            print(f"  #{m['turn']} {m['color']} PASS")
        else:
            print(f"  #{m['turn']} {m['color']} at ({m['x']},{m['y']})")
    print()
