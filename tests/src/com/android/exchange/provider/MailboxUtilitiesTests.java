/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.exchange.provider;

import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.exchange.utility.ExchangeTestCase;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;

public class MailboxUtilitiesTests extends ExchangeTestCase {

    // All tests must build their accounts in mAccount so it will be deleted from live data
    private Account mAccount;
    private ContentResolver mResolver;
    private ContentValues mNullParentKey;

    // Flag sets found in regular email folders that are parents or children
    private static final int PARENT_FLAGS =
            Mailbox.FLAG_ACCEPTS_MOVED_MAIL | Mailbox.FLAG_HOLDS_MAIL |
            Mailbox.FLAG_HAS_CHILDREN | Mailbox.FLAG_CHILDREN_VISIBLE;
    private static final int CHILD_FLAGS =
            Mailbox.FLAG_ACCEPTS_MOVED_MAIL | Mailbox.FLAG_HOLDS_MAIL;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAccount = null;
        mResolver = mProviderContext.getContentResolver();
        mNullParentKey = new ContentValues();
        mNullParentKey.putNull(Mailbox.PARENT_KEY);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        // If we've created and saved an account, delete it
        if (mAccount != null && mAccount.mId > 0) {
            mResolver.delete(
                    ContentUris.withAppendedId(Account.CONTENT_URI, mAccount.mId), null, null);
        }
    }

    public void testSetupParentKeyAndFlag() {
        // Set up account and various mailboxes with/without parents
        mAccount = setupTestAccount("acct1", true);
        Mailbox box1 = EmailContentSetupUtils.setupMailbox("box1", mAccount.mId, true,
                mProviderContext, Mailbox.TYPE_DRAFTS);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox("box2", mAccount.mId, true,
                mProviderContext, Mailbox.TYPE_OUTBOX, box1);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox("box3", mAccount.mId, true,
                mProviderContext, Mailbox.TYPE_ATTACHMENT, box1);
        Mailbox box4 = EmailContentSetupUtils.setupMailbox("box4", mAccount.mId, true,
                mProviderContext, Mailbox.TYPE_NOT_SYNCABLE + 64, box3);
        Mailbox box5 = EmailContentSetupUtils.setupMailbox("box5", mAccount.mId, true,
                mProviderContext, Mailbox.TYPE_MAIL, box3);

        // To make this work, we need to manually set parentKey to null for all mailboxes
        // This simulates an older database needing update
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Hand-create the account selector for our account
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Fix up the database and restore the mailboxes
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);
        box4 = Mailbox.restoreMailboxWithId(mProviderContext, box4.mId);
        box5 = Mailbox.restoreMailboxWithId(mProviderContext, box5.mId);

        // Test that flags and parent key are set properly
        assertEquals(Mailbox.FLAG_HOLDS_MAIL | Mailbox.FLAG_HAS_CHILDREN |
                Mailbox.FLAG_CHILDREN_VISIBLE, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(Mailbox.FLAG_HOLDS_MAIL, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(Mailbox.FLAG_HAS_CHILDREN | Mailbox.FLAG_CHILDREN_VISIBLE, box3.mFlags);
        assertEquals(box1.mId, box3.mParentKey);

        assertEquals(0, box4.mFlags);
        assertEquals(box3.mId, box4.mParentKey);

        assertEquals(Mailbox.FLAG_HOLDS_MAIL | Mailbox.FLAG_ACCEPTS_MOVED_MAIL, box5.mFlags);
        assertEquals(box3.mId, box5.mParentKey);
    }

    private void simulateFolderSyncChangeHandling(String accountSelector, Mailbox...mailboxes) {
        // Run the parent key analyzer and reload the mailboxes
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        for (Mailbox mailbox: mailboxes) {
            String serverId = mailbox.mServerId;
            MailboxUtilities.setFlagsAndChildrensParentKey(mProviderContext, accountSelector,
                    serverId);
        }
    }

    /**
     * Test three cases of adding a folder to an existing hierarchy.  Case 1:  Add to parent.
     */
    public void testParentKeyAddFolder1() {
        // Set up account and various mailboxes with/without parents
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Run the parent key analyzer to set up the initial hierarchy.
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        // The specific test:  Create a 3rd mailbox and attach it to box1 (already a parent)

        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, false, mProviderContext, Mailbox.TYPE_MAIL, box1);
        box3.mParentKey = Mailbox.PARENT_KEY_UNINITIALIZED;
        box3.save(mProviderContext);
        simulateFolderSyncChangeHandling(accountSelector, box1 /*box3's parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(box1.mId, box3.mParentKey);
    }

    /**
     * Test three cases of adding a folder to an existing hierarchy.  Case 2:  Add to child.
     */
    public void testParentKeyAddFolder2() {
        // Set up account and various mailboxes with/without parents
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Run the parent key analyzer to set up the initial hierarchy.
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);

        // Skipping tests of initial hierarchy - see testParentKeyAddFolder1()

        // The specific test:  Create a 3rd mailbox and attach it to box2 (currently a child)

        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, false, mProviderContext, Mailbox.TYPE_MAIL, box2);
        box3.mParentKey = Mailbox.PARENT_KEY_UNINITIALIZED;
        box3.save(mProviderContext);
        simulateFolderSyncChangeHandling(accountSelector, box2 /*box3's parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(PARENT_FLAGS, box2.mFlags);    // should become a parent
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);     // should be child of box2
        assertEquals(box2.mId, box3.mParentKey);
    }

    /**
     * Test three cases of adding a folder to an existing hierarchy.  Case 3:  Add to root.
     */
    public void testParentKeyAddFolder3() {
        // Set up account and various mailboxes with/without parents
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Run the parent key analyzer to set up the initial hierarchy.
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);

        // Skipping tests of initial hierarchy - see testParentKeyAddFolder1()

        // The specific test:  Create a 3rd mailbox and give it no parent (it's top-level).

        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, false, mProviderContext, Mailbox.TYPE_MAIL);
        box3.mParentKey = Mailbox.PARENT_KEY_UNINITIALIZED;
        box3.save(mProviderContext);

        simulateFolderSyncChangeHandling(accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(-1, box3.mParentKey);
    }

    /**
     * Test three cases of removing a folder from the hierarchy.  Case 1:  Remove from parent.
     */
    public void testParentKeyRemoveFolder1() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has two children.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(box1.mId, box3.mParentKey);

        // The specific test:  Delete box3 and check remaining configuration
        mResolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box3.mId), null, null);
        simulateFolderSyncChangeHandling(accountSelector, box1 /*box3's parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);        // Should still be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertNull(box3);
    }

    /**
     * Test three cases of removing a folder from the hierarchy.  Case 2:  Remove from child.
     */
    public void testParentKeyRemoveFolder2() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has box2 and box2 has box3.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box2);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(PARENT_FLAGS, box2.mFlags);    // becomes a parent
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(box2.mId, box3.mParentKey);

        // The specific test:  Delete box3 and check remaining configuration
        mResolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box3.mId), null, null);
        simulateFolderSyncChangeHandling(accountSelector, box2 /*box3's parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);        // Should still be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);         // Becomes a child
        assertEquals(box1.mId, box2.mParentKey);

        assertNull(box3);
    }

    /**
     * Test three cases of removing a folder from the hierarchy.  Case 3:  Remove from root.
     */
    public void testParentKeyRemoveFolder3() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has box2, box3 is also at root.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(-1, box3.mParentKey);

        // The specific test:  Delete box3 and check remaining configuration
        mResolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box3.mId), null, null);
        simulateFolderSyncChangeHandling(accountSelector);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);        // Should still be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);         // Should still be a child
        assertEquals(box1.mId, box2.mParentKey);

        assertNull(box3);
    }

    /**
     * Test changing a parent from none
     */
    public void testChangeFromNoParentToParent() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has box2, box3 is also at root.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(-1, box3.mParentKey);

        // The specific test:  Give box 3 a new parent (box 2) and check remaining configuration
        ContentValues values = new ContentValues();
        values.put(Mailbox.PARENT_SERVER_ID, box2.mServerId);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box3.mId), values,
                null, null);
        simulateFolderSyncChangeHandling(accountSelector, box2 /*box3's new parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);        // Should still be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(PARENT_FLAGS, box2.mFlags);        // Should now be a parent
        assertEquals(box1.mId, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);         // Should still be a child (of box2)
        assertEquals(box2.mId, box3.mParentKey);
    }

    /**
     * Test changing to no parent from a parent
     */
    public void testChangeFromParentToNoParent() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has box2, box3 is also at root.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(box1.mId, box2.mParentKey);

        // The specific test:  Remove the parent from box2 and check remaining configuration
        ContentValues values = new ContentValues();
        values.putNull(Mailbox.PARENT_SERVER_ID);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2.mId), values,
                null, null);
        // Note: FolderSync handling of changed folder would cause parent key to be uninitialized
        // so we do so here
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2.mId), mNullParentKey,
                null, null);
        simulateFolderSyncChangeHandling(accountSelector, box1 /*box2's old parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(CHILD_FLAGS, box1.mFlags);        // Should no longer be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);        // Should still be a child (no parent)
        assertEquals(-1, box2.mParentKey);
    }

    /**
     * Test changing a parent from one mailbox to another
     */
    public void testChangeParent() {
        // Set up account and mailboxes
        mAccount = setupTestAccount("acct1", true);
        String accountSelector = MailboxColumns.ACCOUNT_KEY + " IN (" + mAccount.mId + ")";

        // Initial configuration for this test:  box1 has box2, box3 is also at root.
        Mailbox box1 = EmailContentSetupUtils.setupMailbox(
                "box1", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box2 = EmailContentSetupUtils.setupMailbox(
                "box2", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL);
        Mailbox box3 = EmailContentSetupUtils.setupMailbox(
                "box3", mAccount.mId, true, mProviderContext, Mailbox.TYPE_MAIL, box1);

        // Manually set parentKey to null for all mailboxes, as if an initial sync or post-upgrade
        mResolver.update(Mailbox.CONTENT_URI, mNullParentKey, null, null);

        // Confirm initial configuration as expected
        MailboxUtilities.fixupUninitializedParentKeys(mProviderContext, accountSelector);
        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(PARENT_FLAGS, box1.mFlags);
        assertEquals(-1, box1.mParentKey);

        assertEquals(CHILD_FLAGS, box2.mFlags);
        assertEquals(-1, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);
        assertEquals(box1.mId, box3.mParentKey);

        // The specific test:  Give box 3 a new parent (box 2) and check remaining configuration
        ContentValues values = new ContentValues();
        values.put(Mailbox.PARENT_SERVER_ID, box2.mServerId);
        mResolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, box3.mId), values,
                null, null);
        // Changes to old and new parent
        simulateFolderSyncChangeHandling(accountSelector, box2 /*box3's new parent*/,
                box1 /*box3's old parent*/);

        box1 = Mailbox.restoreMailboxWithId(mProviderContext, box1.mId);
        box2 = Mailbox.restoreMailboxWithId(mProviderContext, box2.mId);
        box3 = Mailbox.restoreMailboxWithId(mProviderContext, box3.mId);

        // Confirm flags and parent key(s) as expected
        assertEquals(CHILD_FLAGS, box1.mFlags);        // Should no longer be a parent
        assertEquals(-1, box1.mParentKey);

        assertEquals(PARENT_FLAGS, box2.mFlags);        // Should now be a parent
        assertEquals(-1, box2.mParentKey);

        assertEquals(CHILD_FLAGS, box3.mFlags);         // Should still be a child (of box2)
        assertEquals(box2.mId, box3.mParentKey);
    }
}
