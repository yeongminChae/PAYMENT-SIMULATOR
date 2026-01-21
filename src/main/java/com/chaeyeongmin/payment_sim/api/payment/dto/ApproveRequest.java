package com.chaeyeongmin.payment_sim.api.payment.dto;

public class ApproveRequest {
    private String pos_trx;
    private int amount;
    private Card card;

    public String getPos_trx() {
        return pos_trx;
    }

    public void setPos_trx(String pos_trx) {
        this.pos_trx = pos_trx;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public Card getCard() {
        return card;
    }

    public void setCard(Card card) {
        this.card = card;
    }

    public static class Card {
        private String pan;
        private String expiry_yy_mm;

        public String getPan() {
            return pan;
        }

        public void setPan(String pan) {
            this.pan = pan;
        }

        public String getExpiry_yy_mm() {
            return expiry_yy_mm;
        }

        public void setExpiry_yy_mm(String expiry_yy_mm) {
            this.expiry_yy_mm = expiry_yy_mm;
        }
    }
}