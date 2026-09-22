// ngram_baseline.cc —— 用项目自带的 marisa 预测模型当 n-gram 基线
//
// 为什么单独写 C++：`model/predict.marisa` 是 marisa-trie 二进制（0.3.1），
// Python 侧没有对应格式的绑定（PyPI 的 marisa-trie 是另一套格式，读不了它），
// 而 librime 的 predict 数据库又不导出任何查询接口。所以直接链系统装的
// libmarisa，一次加载、批量查询。
//
// 键格式（实测枚举确认，见 `--dump`）：
//
//     <词1> <词2> ... \t <下一个词> \xFF <次数>
//
// 即上下文词之间用单个 ASCII 空格分隔，TAB 之后是候选词，0xFF 之后是十进制次数。
// 上下文里可能有空格，所以候选词的起点只能靠“前缀长度”定位，不能靠找 TAB。
//
// 用法：
//     ngram_baseline --model predict.marisa --stats
//     ngram_baseline --model predict.marisa --input queries.tsv --topk 5
//
// queries.tsv 每行 `id<TAB>上下文`；同一个 id 可以出现多次（调用方按“长上下文在前”
// 依次给出回退串），只取第一个命中的那次——这与 app 现有“从长到短找第一个有结果的阶”
// 行为一致，基线才有可比性。
//
// 编译：g++ -O2 -std=c++17 -o ngram_baseline ngram_baseline.cc -lmarisa

#include <marisa.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr char kCountSep = '\xFF';

struct Candidate {
  std::string word;
  long long count = 0;
};

// 排序：次数降序，同次数按 UTF-8 字节序升序，保证基线可复现
bool Better(const Candidate &a, const Candidate &b) {
  if (a.count != b.count) return a.count > b.count;
  return a.word < b.word;
}

std::string EscapeForDump(const char *ptr, std::size_t len) {
  std::string out;
  for (std::size_t i = 0; i < len; ++i) {
    unsigned char c = static_cast<unsigned char>(ptr[i]);
    if (c == '\t') {
      out += "\\t";
    } else if (c == '\n') {
      out += "\\n";
    } else if (c < 0x20 || c >= 0x7F) {
      char buf[8];
      std::snprintf(buf, sizeof(buf), "\\x%02X", c);
      out += buf;
    } else {
      out += static_cast<char>(c);
    }
  }
  return out;
}

// 在 context + '\t' 前缀下枚举所有键。上下文一旦确定，匹配数 = 该上下文的不同
// 下一个词数量（通常几十条），所以这里不需要额外剪枝。
std::vector<Candidate> Query(const marisa::Trie &trie, const std::string &context,
                            std::size_t max_scan) {
  std::string prefix = context;
  prefix += '\t';
  std::vector<Candidate> out;
  marisa::Agent agent;
  agent.set_query(prefix.c_str(), prefix.size());
  std::size_t scanned = 0;
  while (trie.predictive_search(agent)) {
    if (++scanned > max_scan) break;
    const char *ptr = agent.key().ptr();
    std::size_t len = agent.key().length();
    if (len <= prefix.size()) continue;
    const char *rest = ptr + prefix.size();
    std::size_t rest_len = len - prefix.size();
    const char *sep = static_cast<const char *>(std::memchr(rest, kCountSep, rest_len));
    Candidate c;
    if (sep == nullptr) {
      c.word.assign(rest, rest_len);
      c.count = 0;
    } else {
      c.word.assign(rest, static_cast<std::size_t>(sep - rest));
      c.count = std::strtoll(sep + 1, nullptr, 10);
    }
    if (!c.word.empty()) out.push_back(std::move(c));
  }
  std::sort(out.begin(), out.end(), Better);
  return out;
}

void PrintCandidates(const std::vector<Candidate> &cands, std::size_t topk) {
  for (std::size_t i = 0; i < cands.size() && i < topk; ++i) {
    std::cout << '\t' << cands[i].word << '\t' << cands[i].count;
  }
}

// 同 id 多次出现时只保留第一次命中，所以要按首次出现顺序输出
struct QueryGroup {
  std::string id;
  std::vector<std::string> contexts;
};

std::vector<QueryGroup> ReadQueries(const char *path, bool *ok) {
  std::vector<QueryGroup> groups;
  std::unordered_map<std::string, std::size_t> index;
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    *ok = false;
    return groups;
  }
  std::string line;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty()) continue;
    std::size_t tab = line.find('\t');
    if (tab == std::string::npos) continue;
    std::string id = line.substr(0, tab);
    std::string ctx = line.substr(tab + 1);
    auto it = index.find(id);
    if (it == index.end()) {
      index.emplace(id, groups.size());
      groups.push_back({id, {ctx}});
    } else {
      groups[it->second].contexts.push_back(ctx);
    }
  }
  *ok = true;
  return groups;
}

// 这份 trie 有 3 个子 trie（librime 攒的），`reverse_lookup(id)` 在这类结构上
// 按 id 取键取不到各自不同的键，只能从头做 predictive_search 顺序枚举。
int ForEachKey(const marisa::Trie &trie,
               const std::function<bool(const char *, std::size_t)> &fn) {
  marisa::Agent agent;
  agent.set_query("", 0);
  while (trie.predictive_search(agent)) {
    if (!fn(agent.key().ptr(), agent.key().length())) break;
  }
  return 0;
}

int DoStats(const marisa::Trie &trie) {
  std::size_t total = 0;
  std::size_t multi = 0;
  std::size_t no_sep = 0;
  std::size_t max_ctx_words = 0;
  ForEachKey(trie, [&](const char *ptr, std::size_t len) {
    ++total;
    const char *tab = static_cast<const char *>(std::memchr(ptr, '\t', len));
    if (tab == nullptr) return true;
    std::size_t ctx_len = static_cast<std::size_t>(tab - ptr);
    std::size_t words = 1;
    for (std::size_t i = 0; i < ctx_len; ++i) {
      if (ptr[i] == ' ') ++words;
    }
    if (words > 1) ++multi;
    if (words > max_ctx_words) max_ctx_words = words;
    if (std::memchr(tab, kCountSep, len - ctx_len) == nullptr) ++no_sep;
    return true;
  });
  std::cout << "keys=" << trie.num_keys() << "\n";
  std::cout << "keys_enumerated=" << total << "\n";
  std::cout << "contexts_multiword=" << multi << "\n";
  std::cout << "contexts_total=" << total << "\n";
  std::cout << "max_context_words=" << max_ctx_words << "\n";
  std::cout << "keys_without_count_sep=" << no_sep << "\n";
  return 0;
}

void Usage() {
  std::cout <<
      "usage: ngram_baseline --model <predict.marisa> [--input queries.tsv] [--topk 5]\n"
      "                      [--max-scan 200000] [--stats] [--dump N] [--strict]\n";
}

}  // namespace

int main(int argc, char **argv) {
  const char *model = nullptr;
  const char *input = nullptr;
  std::size_t topk = 5;
  std::size_t max_scan = 200000;
  std::size_t dump = 0;
  bool stats = false;
  bool strict = false;

  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&](const char *name) -> const char * {
      if (i + 1 >= argc) {
        std::cerr << "missing value for " << name << "\n";
        std::exit(2);
      }
      return argv[++i];
    };
    if (a == "--model") {
      model = next("--model");
    } else if (a == "--input") {
      input = next("--input");
    } else if (a == "--topk") {
      topk = std::strtoul(next("--topk"), nullptr, 10);
    } else if (a == "--max-scan") {
      max_scan = std::strtoul(next("--max-scan"), nullptr, 10);
    } else if (a == "--dump") {
      dump = std::strtoul(next("--dump"), nullptr, 10);
    } else if (a == "--stats") {
      stats = true;
    } else if (a == "--strict") {
      strict = true;
    } else if (a == "--help" || a == "-h") {
      Usage();
      return 0;
    } else {
      std::cerr << "unknown argument: " << a << "\n";
      Usage();
      return 2;
    }
  }

  if (model == nullptr || (!stats && input == nullptr && dump == 0)) {
    Usage();
    return 2;
  }

  marisa::Trie trie;
  try {
    trie.mmap(model);
  } catch (const marisa::Exception &e) {
    std::cerr << "ngram_baseline: cannot load " << model << ": " << e.what() << "\n";
    return strict ? 1 : 0;
  }
  if (trie.num_keys() == 0) {
    std::cerr << "ngram_baseline: " << model << " has 0 keys\n";
    return strict ? 1 : 0;
  }

  if (dump > 0) {
    std::size_t n = 0;
    ForEachKey(trie, [&](const char *ptr, std::size_t len) {
      std::cout << EscapeForDump(ptr, len) << "\n";
      return ++n < dump;
    });
    return 0;
  }
  if (stats) return DoStats(trie);

  bool ok = false;
  std::vector<QueryGroup> groups = ReadQueries(input, &ok);
  if (!ok) {
    std::cerr << "ngram_baseline: cannot read " << input << "\n";
    return strict ? 1 : 0;
  }
  for (const QueryGroup &g : groups) {
    std::vector<Candidate> hit;
    for (const std::string &ctx : g.contexts) {
      hit = Query(trie, ctx, max_scan);
      if (!hit.empty()) break;
    }
    std::cout << g.id;
    PrintCandidates(hit, topk);
    std::cout << "\n";
  }
  std::cout.flush();
  return 0;
}
