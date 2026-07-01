#!/usr/bin/env python3
"""
批量上传知识库文档性能测试脚本

轮询向量化完成状态：旧版通过 actuator metrics（app.ai.vectorize.documents）判断，
知识库向量化迁至 Spring 事件 + 补偿任务后该指标已不存在，改为轮询 list 接口
统计 docStatus=VECTOR_STORED 的数量是否达到上传成功数。
"""
import requests
import time
from pathlib import Path
from datetime import datetime

BASE_URL = "http://localhost:8081"
DOC_DIR = r"C:\Users\zlr\Desktop\456"
MAX_DOCS = 12
POLL_INTERVAL = 5
MAX_WAIT = 600


def main():
    print("=" * 50)
    print("批量上传知识库文档性能测试")
    print("=" * 50)

    # Step 1: 扫描文档
    print("\n[Step 1] 扫描文档...")
    doc_dir = Path(DOC_DIR)
    docs = sorted(doc_dir.glob("*.pdf"))[:MAX_DOCS]
    total_size = sum(doc.stat().st_size for doc in docs) / (1024 * 1024)
    print(f"找到 {len(docs)} 个文档，总大小: {total_size:.2f} MB")

    # Step 2: 批量上传
    print("\n[Step 2] 批量上传文档...")
    kb_name = f"perf-test-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    start_time = time.time()
    success_count = 0
    kb_ids = []
    doc_upload_elapsed = {}

    for doc in docs:
        print(f"  上传: {doc.name} ({doc.stat().st_size / (1024 * 1024):.2f} MB)...")
        doc_start = time.time()
        try:
            with open(doc, "rb") as f:
                files = {"file": (doc.name, f, "application/pdf")}
                data = {"name": kb_name}
                upload_resp = requests.post(
                    f"{BASE_URL}/api/knowledgebase/upload",
                    files=files,
                    data=data,
                    timeout=120
                )
                upload_data = upload_resp.json()
                # 后端返回 Result<Map>：{code, message, data:{knowledgeBase:{id}, storage:{...}, duplicate}}
                if upload_data.get("success") or upload_data.get("code") == 200:
                    kb_info = upload_data.get("data", {}).get("knowledgeBase", {})
                    kb_id = kb_info.get("id")
                    if kb_id is not None:
                        split_resp = requests.post(
                            f"{BASE_URL}/api/knowledgebase/{kb_id}/split",
                            json={},
                            timeout=120
                        )
                        split_data = split_resp.json()
                        if not (split_data.get("success") or split_data.get("code") == 200):
                            print(f"    [SPLIT FAILED]: {split_data.get('message')}")
                            continue
                    print("    [OK]")
                    success_count += 1
                    kb_info = upload_data.get("data", {}).get("knowledgeBase", {})
                    if "id" in kb_info:
                        kb_ids.append(kb_info["id"])
                    doc_upload_elapsed[doc.name] = time.time() - doc_start
                else:
                    print(f"    [FAILED]: {upload_data.get('message')}")
        except Exception as e:
            print(f"    [ERROR]: {e}")

    upload_end = time.time()
    upload_elapsed = upload_end - start_time
    print(f"\n上传完成，耗时: {upload_elapsed:.2f} 秒")
    print(f"成功: {success_count}/{len(docs)}")

    # Step 3: 等待向量化完成（轮询 list 接口统计 docStatus=VECTOR_STORED）
    print("\n[Step 3] 等待向量化完成...")
    print(f"  轮询知识库状态，等待 {success_count} 个文档向量化完成（最多等待 {MAX_WAIT} 秒）...")
    poll_start = time.time()
    vectorized_count = 0

    while True:
        time.sleep(POLL_INTERVAL)
        try:
            # 用 kb_id 逐个查详情，统计 docStatus=VECTOR_STORED 的数量
            vectorized_count = 0
            for kb_id in kb_ids:
                try:
                    detail_resp = requests.get(
                        f"{BASE_URL}/api/knowledgebase/{kb_id}", timeout=5
                    )
                    detail_data = detail_resp.json()
                    item = detail_data.get("data", {})
                    if item.get("docStatus") == "VECTOR_STORED":
                        vectorized_count += 1
                except Exception:
                    pass

            print(f"  向量化进度: {vectorized_count} / {success_count}")

            if vectorized_count >= success_count and success_count > 0:
                print("  [DONE] All documents vectorized!")
                break
        except Exception as e:
            print(f"  轮询状态失败: {e}")

        elapsed = time.time() - poll_start
        if elapsed > MAX_WAIT:
            print(f"  [TIMEOUT] Waited more than {MAX_WAIT} seconds")
            break

    total_end = time.time()
    total_elapsed = total_end - start_time
    vectorize_wait = total_end - upload_end

    # Step 4: 输出报告
    print("\n" + "=" * 50)
    print("性能测试报告")
    print("=" * 50)
    if kb_ids:
        print(f"知识库 ID: {kb_ids[0] if len(kb_ids) == 1 else kb_ids}")
    print(f"上传文档数: {len(docs)}")
    print(f"总大小: {total_size:.2f} MB")
    print(f"上传耗时: {upload_elapsed:.2f} 秒")
    print(f"向量化等待耗时: {vectorize_wait:.2f} 秒")
    print(f"总耗时（上传 + 向量化）: {total_elapsed:.2f} 秒")
    print(f"向量化完成: {vectorized_count}/{success_count}")

    if doc_upload_elapsed:
        avg_upload = sum(doc_upload_elapsed.values()) / len(doc_upload_elapsed)
        max_upload = max(doc_upload_elapsed.values())
        print(f"单文档平均上传耗时: {avg_upload:.2f} 秒")
        print(f"单文档最大上传耗时: {max_upload:.2f} 秒")

    print("\n测试完成！")


if __name__ == "__main__":
    main()
