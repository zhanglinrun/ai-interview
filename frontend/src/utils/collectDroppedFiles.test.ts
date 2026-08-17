import { describe, expect, it } from 'vitest';
import { collectDroppedFiles, matchesAccept, mergeUniqueFiles } from './collectDroppedFiles';

describe('matchesAccept', () => {
  it('按扩展名过滤，并跳过隐藏文件', () => {
    expect(matchesAccept('note.md', '.pdf,.md')).toBe(true);
    expect(matchesAccept('note.MD', '.md')).toBe(true);
    expect(matchesAccept('slide.pptx', '.pdf,.md')).toBe(false);
    expect(matchesAccept('.DS_Store', '.md')).toBe(false);
  });
});

describe('mergeUniqueFiles', () => {
  it('追加新文件且按 name/size/mtime 去重', () => {
    const first = new File(['a'], 'a.md', { type: 'text/markdown' });
    const same = new File(['a'], 'a.md', { type: 'text/markdown', lastModified: first.lastModified });
    const second = new File(['b'], 'b.md', { type: 'text/markdown' });

    expect(mergeUniqueFiles([first], [same, second])).toEqual([first, second]);
  });
});

describe('collectDroppedFiles', () => {
  it('没有 directory entry 时回退到 FileList，并按 accept 过滤', async () => {
    const markdown = new File(['# hi'], 'note.md', { type: 'text/markdown' });
    const image = new File(['x'], 'cover.png', { type: 'image/png' });
    const dataTransfer = {
      items: [],
      files: [markdown, image],
    } as unknown as DataTransfer;

    await expect(collectDroppedFiles(dataTransfer, '.md,.pdf')).resolves.toEqual([markdown]);
  });
});
