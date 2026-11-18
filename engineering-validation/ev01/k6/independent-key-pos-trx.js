// seq 0001..9999를 한 번만 사용한다. 고갈된 번호는 재사용하지 않는다.
/**
 * 예약 슬롯 안에서 VU마다 posNo를 분리하고 iteration마다 새 sequence를 부여한다.
 * wrapper가 실행 간 슬롯 중복을 막고, 이 함수는 한 실행 안의 범위와 고갈을 검사한다.
 */
export function makePosTrx(store, date, base, vus, vu, iteration) {
  if (!/^\d{4}$/.test(store) || !/^\d{8}$/.test(date) ||
      !Number.isInteger(base) || base < 0 || base + vus > 10000 ||
      !Number.isInteger(vus) || vus < 1 || !Number.isInteger(vu) || vu < 1 || vu > vus ||
      !Number.isInteger(iteration) || iteration < 0 || iteration >= 9999) {
    throw new Error('posTrx range exhausted or invalid input');
  }
  return `${store}-${date}-${String(base + vu - 1).padStart(4, '0')}-${String(iteration + 1).padStart(4, '0')}`;
}
