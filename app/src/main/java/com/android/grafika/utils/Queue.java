package com.android.grafika.utils;

import android.util.Log;

public class Queue {

    private byte[] buffer;
    private int head;
    private int tail;
    private int count;
    private int size;


    public void init(int n) {
        buffer = new byte[n];
        size = n;
        head = 0;
        tail = 0;
        count = 0;
    }


    public void add(byte data) {
        if (size == count) {
            get();
        }

        if (tail == size) {
            tail = 0;
        }

        buffer[tail] = data;
        tail++;
        count++;
    }

    public byte get() {
        if (count == 0) {
            Log.d("mmm", "队列为空");
            return -1;
        }

        if (head == size) {
            head = 0;
        }
        byte data = buffer[head];
        head++;
        count--;
        return data;
    }


    public void addAll(byte[] data) {
        synchronized (this) {
            for (byte b : data) {
                add(b);
            }
        }
    }


    public int getAll(byte[] data, int len) {
        synchronized (this) {
            if (count < len) {
                return -1;
            }
            int j = 0;
            for (int i = 0; i < len; i++) {
                byte b = get();
                data[i] = b;
                j++;
            }

            return j;
        }

    }

}
