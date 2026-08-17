const HIDDEN_FILE = /^\./;

export function matchesAccept(fileName: string, accept?: string): boolean {
  if (!fileName || HIDDEN_FILE.test(fileName)) {
    return false;
  }
  if (!accept || accept === '*' || accept === '*/*') {
    return true;
  }
  const name = fileName.toLowerCase();
  return accept.split(',').some((token) => {
    const ext = token.trim().toLowerCase();
    return ext.startsWith('.') && name.endsWith(ext);
  });
}

export function mergeUniqueFiles(previous: File[], incoming: File[]): File[] {
  const seen = new Set(previous.map(fileKey));
  const merged = [...previous];
  for (const file of incoming) {
    const key = fileKey(file);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    merged.push(file);
  }
  return merged;
}

function fileKey(file: File): string {
  return `${file.webkitRelativePath || file.name}:${file.size}:${file.lastModified}`;
}

/**
 * 拖入文件夹时，浏览器默认只给第一层 FileList。
 * 用 webkitGetAsEntry 递归收集，避免「选了 40 个、实际只带上 8 个」。
 */
export async function collectDroppedFiles(
  dataTransfer: DataTransfer,
  accept?: string,
): Promise<File[]> {
  const collected: File[] = [];
  const items = dataTransfer.items;
  const tasks: Promise<void>[] = [];

  if (items && items.length > 0) {
    for (let index = 0; index < items.length; index += 1) {
      const item = items[index];
      const entry = item.webkitGetAsEntry?.() ?? null;
      if (entry) {
        tasks.push(walkEntry(entry, collected));
        continue;
      }
      const file = item.getAsFile();
      if (file) {
        collected.push(file);
      }
    }
    await Promise.all(tasks);
  }

  if (collected.length === 0) {
    collected.push(...Array.from(dataTransfer.files ?? []));
  }

  return collected.filter((file) => matchesAccept(file.name, accept));
}

async function walkEntry(entry: FileSystemEntry, out: File[]): Promise<void> {
  if (entry.isFile) {
    const file = await readFileEntry(entry as FileSystemFileEntry);
    if (file) {
      out.push(file);
    }
    return;
  }
  if (!entry.isDirectory) {
    return;
  }
  const children = await readAllDirectoryEntries(entry as FileSystemDirectoryEntry);
  for (const child of children) {
    await walkEntry(child, out);
  }
}

function readFileEntry(entry: FileSystemFileEntry): Promise<File | null> {
  return new Promise((resolve) => {
    entry.file(resolve, () => resolve(null));
  });
}

function readAllDirectoryEntries(directory: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = directory.createReader();
  const all: FileSystemEntry[] = [];

  return new Promise((resolve, reject) => {
    const readBatch = () => {
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all);
          return;
        }
        all.push(...batch);
        readBatch();
      }, reject);
    };
    readBatch();
  });
}
