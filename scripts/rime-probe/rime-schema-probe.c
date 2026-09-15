// SPDX-License-Identifier: Apache-2.0
//
// rime-schema-probe —— 在**开发机上**直接跑 librime，验证方案数据的行为。
//
// 为什么需要它：本项目的方案数据（万象拼音）打包在 assets/resource.zip 里，
// 「模糊音有没有生效」「简拼还能不能出成语」「候选数涨了多少」这类问题如果
// 只能靠刷 APK 到手机上试，一轮就是十几分钟，而且看不到候选总数。这个探针
// 用系统的 librime 加载同一份 shared/ 数据，几秒钟就能给出确定答案 ——
// 改 speller/algebra 之前先在这里验证，再谈打包。
//
// 编译（需要 librime 的开发头文件，Arch: extra/librime 自带 /usr/include/rime_api.h）：
//     gcc -O2 -o rime-schema-probe rime-schema-probe.c -lrime
//
// 用法见同目录 README.md。

#include <rime_api.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_EXPECT 8
#define PREVIEW 6

static RimeApi* api = NULL;
static const char* g_expect[MAX_EXPECT];
static int g_expect_count = 0;

static void print_usage(const char* argv0) {
  fprintf(stderr,
          "用法: %s <shared_dir> <user_dir> [选项] <输入码> [输入码...]\n"
          "\n"
          "选项:\n"
          "  --schema <id>    只测某个方案（默认 wanxiang）\n"
          "  --all-schemas    遍历所有已部署方案\n"
          "  --deploy         先跑一次部署（首次必须加；已有部署产物时不必加）\n"
          "  --expect <词>    报告该词在候选里的位置（可多次指定）\n",
          argv0);
}

/** 把整个候选列表跑一遍，返回总数，并记录期望词的位置。 */
static int scan_candidates(RimeSessionId s, char* first_out, size_t first_size) {
  RimeCandidateListIterator it;
  memset(&it, 0, sizeof(it));
  int total = 0;
  int hit[MAX_EXPECT];
  for (int i = 0; i < g_expect_count; ++i) hit[i] = -1;

  if (first_out && first_size) first_out[0] = '\0';
  if (!api->candidate_list_begin(s, &it)) return 0;

  while (api->candidate_list_next(&it)) {
    const char* text = it.candidate.text ? it.candidate.text : "";
    if (total == 0 && first_out && first_size) {
      snprintf(first_out, first_size, "%s", text);
    }
    for (int i = 0; i < g_expect_count; ++i) {
      if (hit[i] < 0 && strcmp(text, g_expect[i]) == 0) hit[i] = total;
    }
    ++total;
  }
  api->candidate_list_end(&it);

  for (int i = 0; i < g_expect_count; ++i) {
    if (hit[i] >= 0) {
      printf("      · 「%s」在第 %d 位\n", g_expect[i], hit[i] + 1);
    } else {
      printf("      · 「%s」**不在候选里**\n", g_expect[i]);
    }
  }
  return total;
}

static void probe_input(RimeSessionId s, const char* schema, const char* input) {
  api->clear_composition(s);
  if (!api->select_schema(s, schema)) {
    printf("  [%s] 选择方案失败（未部署？）\n", schema);
    return;
  }

  RIME_STRUCT(RimeStatus, st);
  char schema_name[64] = "?";
  if (api->get_status(s, &st)) {
    snprintf(schema_name, sizeof(schema_name), "%s", st.schema_name ? st.schema_name : "?");
    api->free_status(&st);
  }

  for (const char* p = input; *p; ++p) {
    api->process_key(s, (int)(unsigned char)*p, 0);
  }

  printf("\n  %-12s [%s / %s]\n", input, schema, schema_name);

  RIME_STRUCT(RimeContext, ctx);
  if (api->get_context(s, &ctx)) {
    printf("      preedit = %s\n", ctx.composition.preedit ? ctx.composition.preedit : "");
    int n = ctx.menu.num_candidates < PREVIEW ? ctx.menu.num_candidates : PREVIEW;
    for (int i = 0; i < n; ++i) {
      printf("      %d. %s\n", i + 1,
             ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
    }
    api->free_context(&ctx);
  }

  char first[64];
  int total = scan_candidates(s, first, sizeof(first));
  printf("      候选总数 = %d，首选 = %s\n", total, first);
}

int main(int argc, char** argv) {
  if (argc < 4) {
    print_usage(argv[0]);
    return 2;
  }

  const char* shared = argv[1];
  const char* user = argv[2];
  const char* schema = "wanxiang";
  int all_schemas = 0;
  int do_deploy = 0;
  const char* inputs[64];
  int input_count = 0;

  for (int i = 3; i < argc; ++i) {
    if (strcmp(argv[i], "--schema") == 0 && i + 1 < argc) {
      schema = argv[++i];
    } else if (strcmp(argv[i], "--all-schemas") == 0) {
      all_schemas = 1;
    } else if (strcmp(argv[i], "--deploy") == 0) {
      do_deploy = 1;
    } else if (strcmp(argv[i], "--expect") == 0 && i + 1 < argc) {
      if (g_expect_count < MAX_EXPECT) g_expect[g_expect_count++] = argv[++i];
    } else if (argv[i][0] == '-' && argv[i][1] == '-') {
      fprintf(stderr, "未知选项: %s\n", argv[i]);
      print_usage(argv[0]);
      return 2;
    } else if (input_count < 64) {
      inputs[input_count++] = argv[i];
    }
  }
  if (input_count == 0) {
    print_usage(argv[0]);
    return 2;
  }

  api = rime_get_api();
  if (!api) {
    fprintf(stderr, "rime_get_api() 返回空，检查 lib rime 是否装好\n");
    return 3;
  }

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared;
  traits.user_data_dir = user;
  traits.distribution_name = "ime";
  traits.distribution_code_name = "ime-rime-probe";
  traits.distribution_version = "0.1";
  traits.app_name = "rime.ime.probe";
  traits.min_log_level = 2;  // ERROR 以上，避免词库编译日志刷屏

  api->setup(&traits);
  api->initialize(&traits);
  printf("librime %s\nshared = %s\nuser   = %s\n", api->get_version(), shared, user);

  if (do_deploy) {
    printf("部署中（首次会编译 80 MB 词库，几分钟）...\n");
    fflush(stdout);
    if (api->start_maintenance(True)) {
      api->join_maintenance_thread();
    }
    printf("部署完成\n");
  }

  RimeSessionId session = api->create_session();
  if (!session) {
    fprintf(stderr, "创建会话失败\n");
    return 5;
  }

  if (all_schemas) {
    RimeSchemaList list;
    memset(&list, 0, sizeof(list));
    if (api->get_schema_list(&list)) {
      printf("\n已部署方案 %zu 个：", list.size);
      for (size_t i = 0; i < list.size; ++i) printf(" %s", list.list[i].schema_id);
      printf("\n");
      for (size_t i = 0; i < list.size; ++i) {
        for (int k = 0; k < input_count; ++k) {
          probe_input(session, list.list[i].schema_id, inputs[k]);
        }
      }
      api->free_schema_list(&list);
    } else {
      fprintf(stderr, "get_schema_list 失败\n");
    }
  } else {
    for (int i = 0; i < input_count; ++i) probe_input(session, schema, inputs[i]);
  }

  api->destroy_session(session);
  api->finalize();
  return 0;
}
