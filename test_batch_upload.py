#!/usr/bin/env python3
"""
批量上传知识库文档性能测试脚本
"""
import requests
import time
import os
from pathlib import Path
from datetime import datetime

BASE_URL = "http://localhost:8081"
DOC_DIR = r"C:\Users\zlr\Desktop\456"
MAX_DOCS = 12

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
    kb_ids = set()
    
    for doc in docs:
        print(f"  上传: {doc.name} ({doc.stat().st_size / (1024 * 1024):.2f} MB)...")
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
                if upload_data.get("success"):
                    print("    [OK]")
                    success_count += 1
                    if "data" in upload_data and "id" in upload_data["data"]:
                        kb_ids.add(upload_data["data"]["id"])
                else:
                    print(f"    [FAILED]: {upload_data.get('message')}")
        except Exception as e:
            print(f"    [ERROR]: {e}")
    
    upload_end = time.time()
    upload_elapsed = upload_end - start_time
    print(f"\n上传完成，耗时: {upload_elapsed:.2f} 秒")
    print(f"成功: {success_count}/{len(docs)}")
    
    # Step 4: 等待向量化完成
    print("\n[Step 4] 等待向量化完成...")
    max_wait = 600
    poll_start = time.time()
    
    while True:
        time.sleep(5)
        
        try:
            metrics_resp = requests.get(
                f"{BASE_URL}/actuator/metrics/app.ai.vectorize.documents",
                timeout=5
            )
            metrics_data = metrics_resp.json()
            measurements = metrics_data.get("measurements", [])
            
            for m in measurements:
                if m.get("statistic") == "COUNT":
                    success_count_metric = int(m.get("value", 0))
                    print(f"  向量化进度: {success_count_metric} / {success_count}")
                    
                    if success_count_metric >= success_count:
                        print("  [DONE] All documents vectorized!")
                        break
            else:
                continue
            break
        except Exception as e:
            print(f"  等待指标就绪... ({e})")
        
        elapsed = time.time() - poll_start
        if elapsed > max_wait:
            print(f"  [TIMEOUT] Waited more than {max_wait} seconds")
            break
    
    total_end = time.time()
    total_elapsed = total_end - start_time
    
    # Step 5: 输出报告
    print("\n" + "=" * 50)
    print("性能测试报告")
    print("=" * 50)
    if kb_ids:
        print(f"知识库 ID: {list(kb_ids)[0] if len(kb_ids) == 1 else kb_ids}")
    print(f"上传文档数: {len(docs)}")
    print(f"总大小: {total_size:.2f} MB")
    print(f"上传耗时: {upload_elapsed:.2f} 秒")
    print(f"总耗时（上传 + 向量化）: {total_elapsed:.2f} 秒")
    
    # 查询最终指标
    try:
        latency_resp = requests.get(
            f"{BASE_URL}/actuator/metrics/app.ai.vectorize.document_latency",
            timeout=5
        )
        latency_data = latency_resp.json()
        measurements = latency_data.get("measurements", [])
        
        for m in measurements:
            if m.get("statistic") == "MEAN":
                print(f"单文档平均耗时: {m.get('value', 0):.2f} 秒")
            elif m.get("statistic") == "MAX":
                print(f"单文档最大耗时: {m.get('value', 0):.2f} 秒")
    except Exception as e:
        print(f"无法获取耗时指标: {e}")
    
    print("\n测试完成！")

if __name__ == "__main__":
    main()
