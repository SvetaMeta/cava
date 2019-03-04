/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package net.consensys.cava.eth.repository

import net.consensys.cava.bytes.Bytes
import net.consensys.cava.bytes.Bytes32
import net.consensys.cava.eth.Block
import net.consensys.cava.eth.BlockHeader
import net.consensys.cava.eth.Hash
import net.consensys.cava.eth.TransactionReceipt
import net.consensys.cava.kv.KeyValueStore

/**
 * Repository housing blockchain information.
 *
 * This repository allows storing blocks, block headers and metadata about the blockchain, such as forks and head
 * information.
 */
class BlockchainRepository
/**
 * Default constructor.
 *
 * @param chainMetadata the key-value store to store chain metadata
 * @param blockStore the key-value store to store blocks
 * @param blockHeaderStore the key-value store to store block headers
 * @param blockchainIndex the blockchain index to index values
 */
  (
    private val chainMetadata: KeyValueStore,
    private val blockStore: KeyValueStore,
    private val blockHeaderStore: KeyValueStore,
    private val transactionReceiptsStore: KeyValueStore,
    private val blockchainIndex: BlockchainIndex
  ) {

  companion object {

    val GENESIS_BLOCK = Bytes.wrap("genesisBlock".toByteArray())

    /**
     * Initializes a blockchain repository with metadata, placing it in key-value stores.
     *
     * @return a new blockchain repository made from the metadata passed in parameter.
     */
    suspend fun init(
      blockStore: KeyValueStore,
      blockHeaderStore: KeyValueStore,
      chainMetadata: KeyValueStore,
      transactionReceiptsStore: KeyValueStore,
      blockchainIndex: BlockchainIndex,
      genesisBlock: Block
    ): BlockchainRepository {
      val repo = BlockchainRepository(chainMetadata,
        blockStore,
        blockHeaderStore,
        transactionReceiptsStore,
        blockchainIndex)
      repo.setGenesisBlock(genesisBlock)
      repo.storeBlock(genesisBlock)
      return repo
    }
  }

  /**
   * Stores a block in the repository.
   *
   * @param block the block to store
   * @return a handle to the storage operation completion
   */
  suspend fun storeBlock(block: Block) {
    blockStore.put(block.header().hash().toBytes(), block.toBytes())
    blockHeaderStore.put(block.header().hash().toBytes(), block.header().toBytes())
    indexBlockHeader(block.header())
  }

  /**
   * Store all the transaction receipts of a block in the repository.
   *
   * Transaction receipts should be ordered by the transactions order of the block.
   *
   * @param transactionReceipts the transaction receipts to store
   * @param txHash the hash of the transaction
   * @param blockHash the hash of the block that this transaction belongs to
   */
  suspend fun storeTransactionReceipts(vararg transactionReceipts: TransactionReceipt, txHash: Hash, blockHash: Hash) {
    for (i in 0 until transactionReceipts.size) {
      storeTransactionReceipt(transactionReceipts[i], i, txHash, blockHash)
    }
  }

  /**
   * Stores a transaction receipt in the repository.
   *
   * @param transactionReceipt the transaction receipt to store
   * @param txIndex the index of the transaction in the block
   * @param txHash the hash of the transaction
   * @param blockHash the hash of the block that this transaction belongs to
   */
  suspend fun storeTransactionReceipt(
    transactionReceipt: TransactionReceipt,
    txIndex: Int,
    txHash: Hash,
    blockHash: Hash
  ) {
    transactionReceiptsStore.put(txHash.toBytes(), transactionReceipt.toBytes())
    indexTransactionReceipt(transactionReceipt, txIndex, txHash, blockHash)
  }

  /**
   * Stores a block header in the repository.
   *
   * @param header the block header to store
   * @return handle to the storage operation completion
   */
  suspend fun storeBlockHeader(header: BlockHeader) {
    blockHeaderStore.put(header.hash().toBytes(), header.toBytes())
    indexBlockHeader(header)
  }

  private suspend fun indexBlockHeader(header: BlockHeader) {
    blockchainIndex.index { writer -> writer.indexBlockHeader(header) }
    for (hash in findBlocksByParentHash(header.hash())) {
      blockHeaderStore.get(hash.toBytes())?.let { bytes ->
        indexBlockHeader(BlockHeader.fromBytes(bytes))
      }
    }
  }

  private suspend fun indexTransactionReceipt(
    txReceipt: TransactionReceipt,
    txIndex: Int,
    txHash: Hash,
    blockHash: Hash
  ) {
    blockchainIndex.index {
      it.indexTransactionReceipt(txReceipt, txIndex, txHash, blockHash)
    }
  }

  /**
   * Retrieves a block into the repository as its serialized RLP bytes representation.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the bytes if found
   */
  suspend fun retrieveBlockBytes(blockHash: Hash): Bytes? {
    return retrieveBlockBytes(blockHash.toBytes())
  }

  /**
   * Retrieves a block into the repository as its serialized RLP bytes representation.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the bytes if found
   */
  suspend fun retrieveBlockBytes(blockHash: Bytes): Bytes? {
    return blockStore.get(blockHash)
  }

  /**
   * Retrieves a block into the repository.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block if found
   */
  suspend fun retrieveBlock(blockHash: Hash): Block? {
    return retrieveBlock(blockHash.toBytes())
  }

  /**
   * Retrieves a block into the repository.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block if found
   */
  suspend fun retrieveBlock(blockHash: Bytes): Block? {
    return retrieveBlockBytes(blockHash)?.let { Block.fromBytes(it) } ?: return null
  }

  /**
   * Retrieves a block header into the repository as its serialized RLP bytes representation.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block header bytes if found
   */
  suspend fun retrieveBlockHeaderBytes(blockHash: Hash): Bytes? {
    return retrieveBlockBytes(blockHash.toBytes())
  }

  /**
   * Retrieves a block header into the repository as its serialized RLP bytes representation.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block header bytes if found
   */
  suspend fun retrieveBlockHeaderBytes(blockHash: Bytes): Bytes? {
    return blockHeaderStore.get(blockHash)
  }

  /**
   * Retrieves a block header into the repository.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block header if found
   */
  suspend fun retrieveBlockHeader(blockHash: Hash): BlockHeader? {
    return retrieveBlockHeaderBytes(blockHash.toBytes())?.let { BlockHeader.fromBytes(it) } ?: return null
  }

  /**
   * Retrieves a block header into the repository.
   *
   * @param blockHash the hash of the block stored
   * @return a future with the block header if found
   */
  suspend fun retrieveBlockHeader(blockHash: Bytes): BlockHeader? {
    val bytes = retrieveBlockHeaderBytes(blockHash) ?: return null
    return BlockHeader.fromBytes(bytes)
  }

  /**
   * Retrieves the block identified as the chain head
   *
   * @return the current chain head, or the genesis block if no chain head is present.
   */
  suspend fun retrieveChainHead(): Block? {
    return blockchainIndex.findByLargest(BlockHeaderFields.TOTAL_DIFFICULTY)
      ?.let { retrieveBlock(it) } ?: retrieveGenesisBlock()
  }

  /**
   * Retrieves the block header identified as the chain head
   *
   * @return the current chain head header, or the genesis block if no chain head is present.
   */
  suspend fun retrieveChainHeadHeader(): BlockHeader? {
    return blockchainIndex.findByLargest(BlockHeaderFields.TOTAL_DIFFICULTY)
      ?.let { retrieveBlockHeader(it) } ?: retrieveGenesisBlock()?.header()
  }

  /**
   * Retrieves the block identified as the genesis block
   *
   * @return the genesis block
   */
  suspend fun retrieveGenesisBlock(): Block? {
    return chainMetadata.get(GENESIS_BLOCK)?.let { retrieveBlock(it) }
  }

  /**
   * Retrieves all transaction receipts associated with a block.
   *
   * @param blockHash the hash of the block
   * @return all transaction receipts associated with a block, in the correct order
   */
  suspend fun retrieveTransactionReceipts(blockHash: Hash): List<TransactionReceipt?> {
    return blockchainIndex.findBy(TransactionReceiptFields.BLOCK_HASH, blockHash).map {
      transactionReceiptsStore.get(it.toBytes())?.let { TransactionReceipt.fromBytes(it) }
    }
  }

  /**
   * Retrieves a transaction receipt associated with a block and an index
   * @param blockHash the hash of the block
   * @param index the index of the transaction in the block
   */
  suspend fun retrieveTransactionReceipt(blockHash: Hash, index: Int): TransactionReceipt? {
    return blockchainIndex.findByBlockHashAndIndex(blockHash, index)?.let {
      transactionReceiptsStore.get(it.toBytes())?.let { TransactionReceipt.fromBytes(it) }
    }
  }

  /**
   * Retrieves a transaction receipt associated with a block and an index
   * @param txHash the hash of the transaction
   */
  suspend fun retrieveTransactionReceipt(txHash: Hash): TransactionReceipt? {
    return transactionReceiptsStore.get(txHash.toBytes())?.let { TransactionReceipt.fromBytes(it) }
  }

  /**
   * Finds a block according to the bytes, which can be a block number or block hash.
   *
   * @param blockNumberOrBlockHash the number or hash of the block
   * @return the matching blocks
   */
  fun findBlockByHashOrNumber(blockNumberOrBlockHash: Bytes32): List<Hash> {
    return blockchainIndex.findByHashOrNumber(blockNumberOrBlockHash)
  }

  /**
   * Finds hashes of blocks which have a matching parent hash.
   *
   * @param parentHash the parent hash
   * @return the matching blocks
   */
  fun findBlocksByParentHash(parentHash: Hash): List<Hash> {
    return blockchainIndex.findBy(BlockHeaderFields.PARENT_HASH, parentHash)
  }

  private suspend fun setGenesisBlock(block: Block) {
    return chainMetadata
      .put(GENESIS_BLOCK, block.header().hash().toBytes())
  }
}
