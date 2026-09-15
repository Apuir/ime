// SPDX-License-Identifier: Apache-2.0
//
// mini_gram_builder —— 不依赖 librime，按 octagram 的 .gram 格式造一个语法模型。
//
// 格式（对着 plugins/librime-octagram/src/gram_db.cc 与
// src/rime/dict/mapped_file.cc 逆向 + 用真实的 420MB 模型交叉验证过）：
//
//   offset 0   : grammar::Metadata
//                  char     format[32]        = "Rime::Grammar/1.0" + NUL 填充
//                  uint32_t db_checksum       = 0（Load 时不校验）
//                  uint32_t double_array_size = darts 数组的**单元数**（不是字节数）
//                  int32_t  double_array      = 相对自身地址的偏移，恒为 4
//   offset 44  : darts 双数组镜像（单元数 × 4 字节）
//
// 键（key）是 UTF-8 的「上下文 + 词」直接拼接，写入前按 grammar::encode 编码：
//   u < 0x80                -> 1 字节 u（u==0 编码成 0xE0）
//   0x4000 <= u < 0xA000    -> 2 字节 [(u>>8)+0x40, u&0xFF]（常用汉字走这条）
//   其它                    -> 0xE0|len 前缀 + 每 7 位一字节
//
// 值的含义与官方一致：max(0, int(log(词频) * 10000))。
//
// 用法：mini_gram_builder <输出.gram> < 输入.txt
//       输入每行两列，空白分隔：<上下文+词> <词频>
//
// 编译：g++ -O2 -std=c++17 -I<librime>/include -o mini_gram_builder mini_gram_builder.cc

#include <darts.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr int kValueScale = 10000;
constexpr int kMaxEncodedUnicode = 8;
const std::string kFormat = "Rime::Grammar/1.0";

std::string encode_utf8(const std::string& utf8) {
  char buf[kMaxEncodedUnicode * 4];
  char* e = buf;
  size_t i = 0;
  while (i < utf8.size()) {
    unsigned char c = static_cast<unsigned char>(utf8[i]);
    uint32_t u = 0;
    int extra = 0;
    if (c < 0x80) {
      u = c;
    } else if ((c & 0xE0) == 0xC0) {
      u = c & 0x1F;
      extra = 1;
    } else if ((c & 0xF0) == 0xE0) {
      u = c & 0x0F;
      extra = 2;
    } else if ((c & 0xF8) == 0xF0) {
      u = c & 0x07;
      extra = 3;
    } else {
      ++i;
      continue;
    }
    ++i;
    for (int k = 0; k < extra && i < utf8.size(); ++k, ++i) {
      u = (u << 6) | (static_cast<unsigned char>(utf8[i]) & 0x3F);
    }

    if (u < 0x80) {
      *e++ = static_cast<char>(u == 0 ? 0xE0 : u);
    } else if (u >= 0x4000 && u < 0xA000) {
      if ((u & 0xFF) == 0) {
        *e++ = static_cast<char>(0xE1);
        *e++ = static_cast<char>((u >> 8) + 0x40);
      } else {
        *e++ = static_cast<char>((u >> 8) + 0x40);
        *e++ = static_cast<char>(u & 0xFF);
      }
    } else {
      int bits = 32;
      uint32_t v = u;
      while (bits > 0 && (v & 0xFE000000) == 0) {
        bits -= 7;
        v <<= 7;
      }
      int n = (bits + 6) / 7;
      *e++ = static_cast<char>(0xE0 | n);
      while (n-- > 0) {
        *e++ = static_cast<char>(((v >> 25) & 0x7F) | 0x80);
        v <<= 7;
      }
    }
  }
  return std::string(buf, e);
}

void put_u32(std::ofstream& out, uint32_t v) {
  char b[4] = {static_cast<char>(v & 0xFF), static_cast<char>((v >> 8) & 0xFF),
               static_cast<char>((v >> 16) & 0xFF), static_cast<char>((v >> 24) & 0xFF)};
  out.write(b, 4);
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) {
    std::cerr << "用法: " << argv[0] << " <输出.gram> < 输入.txt\n";
    return 2;
  }
  const std::string out_path = argv[1];

  std::vector<std::pair<std::string, double>> data;
  std::string key;
  double value = 0;
  while (std::cin >> key >> value) {
    data.push_back({encode_utf8(key), value});
  }
  if (data.empty()) {
    std::cerr << "输入为空\n";
    return 2;
  }
  std::sort(data.begin(), data.end());

  std::vector<const char*> keys;
  std::vector<int> values;
  keys.reserve(data.size());
  values.reserve(data.size());
  for (const auto& kv : data) {
    keys.push_back(kv.first.c_str());
    values.push_back(std::max(0, static_cast<int>(std::log(kv.second) * kValueScale)));
  }

  Darts::DoubleArray trie;
  if (trie.build(static_cast<int>(data.size()), &keys[0], NULL, &values[0]) != 0) {
    std::cerr << "darts build 失败\n";
    return 1;
  }

  const size_t units = trie.size();
  const size_t image = trie.total_size();

  std::ofstream out(out_path, std::ios::binary | std::ios::trunc);
  if (!out) {
    std::cerr << "打不开输出文件: " << out_path << "\n";
    return 1;
  }
  char format[32] = {};
  std::memcpy(format, kFormat.c_str(), kFormat.size());
  out.write(format, 32);
  put_u32(out, 0);                                  // db_checksum
  put_u32(out, static_cast<uint32_t>(units));       // 单元数
  put_u32(out, 4);                                  // 相对偏移
  out.write(static_cast<const char*>(trie.array()), static_cast<std::streamsize>(image));
  out.close();

  std::cerr << "写出 " << out_path << "：键 " << data.size() << " 条，数组 "
            << units << " 单元，" << (44 + image) << " 字节\n";
  return 0;
}
