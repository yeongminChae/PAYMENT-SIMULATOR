import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('./independent-key-pos-trx.js', import.meta.url), 'utf8');
const { makePosTrx } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const seen = new Set();
for (const base of [0, 200, 9800]) {
  for (let vu = 1; vu <= 200; vu++) {
    for (const iteration of [0, 1, 9998]) {
      const key = makePosTrx('2376','20261027',base,200,vu,iteration);
      assert.match(key, /^\d{4}-\d{8}-\d{4}-\d{4}$/);
      assert.ok(!seen.has(key)); seen.add(key);
    }
  }
}
assert.equal(makePosTrx('2376','20261027',9800,200,200,9998), '2376-20261027-9999-9999');
const sequences = new Set();
for (let i=0; i<9999; i++) sequences.add(makePosTrx('2376','20261027',0,10,1,i));
assert.equal(sequences.size, 9999);
assert.notEqual(makePosTrx('2376','20261027',0,10,1,0), makePosTrx('2376','20261028',0,10,1,0));
for (const args of [[0,200,1,9999],[9801,200,1,0],[0,200,201,0],[0,200,0,0],[0,200,1,-1]]) {
  assert.throws(() => makePosTrx('2376','20261027',...args));
}
console.log('posTrx uniqueness and boundaries: PASS');
