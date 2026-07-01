#!/usr/bin/env python3
"""Convert JPA @Entity classes to MyBatis-Plus annotations."""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1] / "src/main/java"

TABLE_MAP = {
    "KnowledgeBaseVersionEntity": "knowledge_base_version",
}

def table_name(path: pathlib.Path, original: str) -> str:
    if path.stem in TABLE_MAP:
        return TABLE_MAP[path.stem]
    m = re.search(r'@Table\(name\s*=\s*"([^"]+)"', original)
    if m:
        return m.group(1)
    return re.sub(r'(?<!Base)(Entity)$', '', path.stem)
    # fallback snake_case from class name
    name = path.stem.replace("Entity", "")
    return re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()


def convert(path: pathlib.Path) -> None:
    original = path.read_text(encoding="utf-8")
    if "@Entity" not in original:
        return
    text = original
    text = re.sub(r"import jakarta\.persistence\.[^;]+;\n", "", text)
    text = re.sub(r"@Entity\n\s*", "", text)
    text = re.sub(r"@Table\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@Index\([^)]*\),?\s*", "", text)
    text = re.sub(
        r"@Id\n\s*@GeneratedValue\([^)]*\)\n",
        "    @TableId(type = IdType.AUTO)\n",
        text,
    )
    text = re.sub(r"@Id\n\s*", "    @TableId(type = IdType.AUTO)\n", text)
    text = re.sub(r"@Column\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@Column\n\s*", "", text)
    text = re.sub(r"@Enumerated\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@ManyToOne\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@OneToMany\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@ManyToMany\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@JoinColumn\([^)]*\)\n\s*", "", text)
    text = re.sub(r"@JoinTable\([^;]*;\n", "", text, flags=re.DOTALL)
    text = re.sub(r"@GeneratedValue\([^)]*\)\n\s*", "", text)

  # relationship fields -> FK long (keep field names ending in Id)
    imports = (
        "import com.baomidou.mybatisplus.annotation.IdType;\n"
        "import com.baomidou.mybatisplus.annotation.TableId;\n"
        "import com.baomidou.mybatisplus.annotation.TableName;\n"
    )
    if "@TableField" in text or "List<" in text:
        imports += "import com.baomidou.mybatisplus.annotation.TableField;\n"
    if "List<" in text:
        imports += "import java.util.List;\n"

    pkg_idx = text.find("package ")
    import_idx = text.find("import ", pkg_idx)
    if import_idx == -1:
        import_idx = text.find("\n\n", pkg_idx) + 2
    else:
        while True:
            nxt = text.find("import ", import_idx + 7)
            if nxt == -1:
                break
            import_idx = nxt
        import_idx = text.find("\n", import_idx) + 1

    text = text[:import_idx] + imports + text[import_idx:]
    tbl = table_name(path, original)
    text = text.replace("public class", f'@TableName("{tbl}")\npublic class', 1)
    path.write_text(text, encoding="utf-8")
    print(f"converted {path.relative_to(ROOT.parent.parent)}")


def main():
    for path in sorted(ROOT.rglob("*Entity.java")):
        convert(path)


if __name__ == "__main__":
    main()
