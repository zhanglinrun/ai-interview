import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/request';

const uploadKnowledgeBaseBatch = vi.fn();

vi.mock('../api/knowledgebase', () => ({
  knowledgeBaseApi: {
    uploadKnowledgeBaseBatch,
  },
}));

describe('kbUploadQueue', () => {
  afterEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
  });

  it('每个文件单独请求，一个失败不影响其余', async () => {
    uploadKnowledgeBaseBatch.mockImplementation(async (files: File[]) => ({
      success: files.length,
      failed: 0,
      total: files.length,
      duplicate: 0,
      items: files.map((file) => ({ filename: file.name, status: 'success' })),
    }));

    const { enqueueKbBatchUpload, getKbUploadQueueSummary } = await import('./kbUploadQueue');
    const first = new File(['a'], 'a.pdf', { type: 'application/pdf' });
    const second = new File(['b'], 'b.pdf', { type: 'application/pdf' });

    enqueueKbBatchUpload([first, second], { accessibleBy: 'PRIVATE' });

    expect(getKbUploadQueueSummary().total).toBe(2);
    expect(getKbUploadQueueSummary().active).toBe(true);

    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(2);
      expect(getKbUploadQueueSummary().accepted).toBe(2);
      expect(getKbUploadQueueSummary().active).toBe(false);
    }, { timeout: 3000 });
    const sent = uploadKnowledgeBaseBatch.mock.calls.map((call) => call[0][0].name).sort();
    expect(sent).toEqual(['a.pdf', 'b.pdf']);
  });

  it('同时最多两路在传', async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const releases: Array<() => void> = [];
    uploadKnowledgeBaseBatch.mockImplementation((uploaded: File[]) => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      return new Promise((resolve) => {
        releases.push(() => {
          inFlight -= 1;
          resolve({
            success: 1,
            failed: 0,
            total: 1,
            duplicate: 0,
            items: uploaded.map((file) => ({ filename: file.name, status: 'success' })),
          });
        });
      });
    });

    const { enqueueKbBatchUpload, KB_UPLOAD_CONCURRENCY } = await import('./kbUploadQueue');
    const selected = Array.from({ length: KB_UPLOAD_CONCURRENCY + 2 }, (_, index) => (
      new File([String(index)], `file-${index}.md`, { type: 'text/markdown' })
    ));
    enqueueKbBatchUpload(selected, { accessibleBy: 'PRIVATE' });

    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(KB_UPLOAD_CONCURRENCY);
    }, { timeout: 3000 });
    expect(maxInFlight).toBe(KB_UPLOAD_CONCURRENCY);
    expect(releases).toHaveLength(KB_UPLOAD_CONCURRENCY);

    releases.splice(0).forEach((release) => release());
    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(selected.length);
    }, { timeout: 3000 });
    releases.splice(0).forEach((release) => release());
  });

  it('超过单批上限时拆成多次请求', async () => {
    uploadKnowledgeBaseBatch.mockImplementation(async (files: File[]) => ({
      success: files.length,
      failed: 0,
      total: files.length,
      duplicate: 0,
      items: files.map((file) => ({ filename: file.name, status: 'success' })),
    }));

    const { enqueueKbBatchUpload, getKbUploadQueueSummary, KB_UPLOAD_BATCH_SIZE } =
      await import('./kbUploadQueue');
    const selected = Array.from({ length: KB_UPLOAD_BATCH_SIZE + 1 }, (_, index) => (
      new File([String(index)], `file-${index}.md`, { type: 'text/markdown' })
    ));

    enqueueKbBatchUpload(selected, { accessibleBy: 'PRIVATE' });

    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(2);
      expect(getKbUploadQueueSummary().accepted).toBe(selected.length);
    }, { timeout: 3000 });
    expect(uploadKnowledgeBaseBatch.mock.calls[0][0]).toHaveLength(KB_UPLOAD_BATCH_SIZE);
    expect(uploadKnowledgeBaseBatch.mock.calls[1][0]).toHaveLength(1);
  });

  it('遇到网络中断会重试同一文件', async () => {
    uploadKnowledgeBaseBatch
      .mockRejectedValueOnce(new Error('上传失败，可能是网络超时或连接中断，请重试'))
      .mockResolvedValueOnce({
        success: 1,
        failed: 0,
        total: 1,
        duplicate: 0,
        items: [{ filename: 'a.md', status: 'success' }],
      });

    const { enqueueKbBatchUpload, getKbUploadQueueSummary } = await import('./kbUploadQueue');
    enqueueKbBatchUpload(
      [new File(['a'], 'a.md', { type: 'text/markdown' })],
      { accessibleBy: 'PRIVATE' },
    );

    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(2);
      expect(getKbUploadQueueSummary().accepted).toBe(1);
    }, { timeout: 4000 });
  });

  it('遇到限流会重试同一批文件', async () => {
    uploadKnowledgeBaseBatch
      .mockRejectedValueOnce(new ApiError(8001, '请求过于频繁，请稍后再试'))
      .mockResolvedValueOnce({
        success: 1,
        failed: 0,
        total: 1,
        duplicate: 0,
        items: [{ filename: 'a.md', status: 'success' }],
      });

    const { enqueueKbBatchUpload, getKbUploadQueueSummary } = await import('./kbUploadQueue');
    enqueueKbBatchUpload(
      [new File(['a'], 'a.md', { type: 'text/markdown' })],
      { accessibleBy: 'PRIVATE' },
    );

    await vi.waitFor(() => {
      expect(uploadKnowledgeBaseBatch).toHaveBeenCalledTimes(2);
      expect(getKbUploadQueueSummary().accepted).toBe(1);
    }, { timeout: 4000 });
  });

  it('上传进行中不能清掉结果条', async () => {
    uploadKnowledgeBaseBatch.mockImplementation(() => new Promise(() => {}));

    const { enqueueKbBatchUpload, dismissKbUploadQueueSummary, getKbUploadQueueSummary } =
      await import('./kbUploadQueue');
    enqueueKbBatchUpload(
      [new File(['a'], 'a.md', { type: 'text/markdown' })],
      { accessibleBy: 'PRIVATE' },
    );

    expect(getKbUploadQueueSummary().active).toBe(true);
    dismissKbUploadQueueSummary();
    expect(getKbUploadQueueSummary().total).toBe(1);
  });

  it('上传结束后关掉结果条会清掉失败摘要', async () => {
    uploadKnowledgeBaseBatch.mockResolvedValue({
      success: 0,
      failed: 1,
      total: 1,
      duplicate: 0,
      items: [{ filename: 'a.md', status: 'failed', error: '文档内容已存在，请勿重复上传' }],
    });

    const { enqueueKbBatchUpload, dismissKbUploadQueueSummary, getKbUploadQueueSummary } =
      await import('./kbUploadQueue');
    enqueueKbBatchUpload(
      [new File(['a'], 'a.md', { type: 'text/markdown' })],
      { accessibleBy: 'PRIVATE' },
    );
    await vi.waitFor(() => {
      expect(getKbUploadQueueSummary().active).toBe(false);
      expect(getKbUploadQueueSummary().failed).toBe(1);
    }, { timeout: 3000 });

    dismissKbUploadQueueSummary();
    expect(getKbUploadQueueSummary()).toMatchObject({
      total: 0,
      failed: 0,
      active: false,
    });
  });
});
