package scorex.lagonaki.unit

import com.wavesplatform.settings.WalletSettings
import com.wavesplatform.state2.ByteStr
import org.scalatest.{FunSuite, Matchers}
import scorex.wallet.Wallet

class WalletSpecification extends FunSuite with Matchers {

  private val walletSize = 10
  val w = Wallet(WalletSettings(None, "cookies", ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))

  test("wallet - acc creation") {
    w.generateNewAccounts(walletSize)

    w.privateKeyAccounts.size shouldBe walletSize
    w.privateKeyAccounts.map(_.address) shouldBe Seq("3MqMwwHW4v2nSEDHVWoh8RCQL8QrsWLkkeB", "3MuwVgJA8EXHukxo6rcakT5tD6FpvACtitG", "3MuAvUG4EAsG9RP9jaWjewCVmggaQD2t39B", "3MqoX4A3UGBYU7cX2JPs6BCzntNC8K8FBR4", "3N1Q9VVVQtY3GqhwHtJDEyHb3oWBcerZL8X", "3NARifVFHthMDnCwBacXijPB2szAgNTeBCz", "3N6dsnfD88j5yKgpnEavaaJDzAVSRBRVbMY", "3MufvXKZxLuNn5SHcEgGc2Vo7nLWnKVskfJ", "3Myt4tocZmj7o3d1gnuWRrnQWcoxvx5G7Ac", "3N3keodUiS8WLEw9W4BKDNxgNdUpwSnpb3K")
  }

  test("wallet - acc deletion") {

    val head = w.privateKeyAccounts.head
    w.deleteAccount(head)
    assert(w.privateKeyAccounts.size == walletSize - 1)

    w.deleteAccount(w.privateKeyAccounts.head)
    assert(w.privateKeyAccounts.size == walletSize - 2)

    w.privateKeyAccounts.foreach(w.deleteAccount)

    assert(w.privateKeyAccounts.isEmpty)
  }

  test("reopening") {
    val walletFile = Some(scorex.createTestTemporaryFile("wallet", ".dat"))

    val w1 = Wallet(WalletSettings(walletFile, "cookies", ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))
    w1.generateNewAccounts(10)
    val w1privateKeyAccounts = w1.privateKeyAccounts
    val w1nonce = w1.nonce

    val w2 = Wallet(WalletSettings(walletFile, "cookies", None))
    w2.privateKeyAccounts.nonEmpty shouldBe true
    w2.privateKeyAccounts shouldEqual w1privateKeyAccounts
    w2.nonce shouldBe w1nonce
  }
}
