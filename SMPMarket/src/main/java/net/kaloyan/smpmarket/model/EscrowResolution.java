package net.kaloyan.smpmarket.model;

/** What an administrator decides for a listing whose item removal could not be proven. */
public enum EscrowResolution {
    /** The item really left the seller: put the listing on sale. */
    ACTIVATE,
    /** The item really left the seller and should go back to them: create the owner's delivery. */
    RETURN,
    /** The item never left the seller: close the listing without creating any item. */
    ABORT
}
