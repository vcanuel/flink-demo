package com.solidaris;

import org.apache.flink.api.common.time.Time;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Date;

public class Transaction {
        int account_id;
    LocalDateTime transaction_time;
    int amount;

    public Transaction(int accoundId, LocalDateTime transactionTime, int amount) {
        this.account_id = accoundId;
        this.transaction_time = transactionTime;
        this.amount = amount;
    }
}
