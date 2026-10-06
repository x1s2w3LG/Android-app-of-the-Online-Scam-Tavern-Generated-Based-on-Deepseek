// 提取 HTML 里的内联 <script> 做语法检查（不执行）
const fs = require('fs');
let bad = 0;
for (const f of process.argv.slice(2)) {
    const s = fs.readFileSync(f, 'utf8');
    const m = [...s.matchAll(/<script>([\s\S]*?)<\/script>/g)];
    if (!m.length) { console.log('  ' + f + ' -> 没有内联脚本'); continue; }
    m.forEach((x, i) => {
        try {
            new Function(x[1]);
            console.log('  OK  ' + f.split(/[\\/]/).pop() + ' script#' + i + ' (' + x[1].length + ' 字符)');
        } catch (e) {
            bad++;
            console.log('  ERR ' + f.split(/[\\/]/).pop() + ' script#' + i + ': ' + e.message);
        }
    });
}
process.exit(bad ? 1 : 0);
